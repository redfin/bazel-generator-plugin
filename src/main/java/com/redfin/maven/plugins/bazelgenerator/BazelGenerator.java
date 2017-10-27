package com.redfin.maven.plugins.bazelgenerator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Mojo( name = "generate", defaultPhase = LifecyclePhase.PROCESS_SOURCES, requiresDependencyResolution = ResolutionScope.TEST )
public class BazelGenerator
    extends AbstractMojo
{
    @Parameter( defaultValue = "${session}", readonly = true )
    MavenSession session;

    @Parameter( defaultValue = "${project}", readonly = true )
    MavenProject project;

    @Component
    RepositorySystem repositorySystem;

    @Parameter
    private boolean skip = false;

    @Parameter
    private List<ExtraRule> generatedSourceRules = new ArrayList<>();

    @Parameter
    private List<ExtraRule> extraRules = new ArrayList<>();

    @Parameter
    private List<Dependency> toolDependencies = new ArrayList<>();

    // arbitrary json to be passed through to bazel.json
    @Parameter String extraConfig;

    private BazelDependencySupport depSupport;

    static final ConcurrentHashMap<String, Integer> urlResponseCache = new ConcurrentHashMap<>();
    static int urlResponseCacheRefcount = 0;

    public BazelGenerator() { }

    public void execute() throws MojoExecutionException {
        File urlResponseFile = new File(session.getTopLevelProject().getParentFile(), "tools/cache/url_response.json");
        synchronized(urlResponseCache) {
            urlResponseCacheRefcount++;
            if(urlResponseCache.isEmpty()) {
                urlResponseCache.putAll(readUrlResponseCache(urlResponseFile));
            }
        }
        BazelWorkspace workspace = new BazelWorkspace(session, project, urlResponseCache);
        File projectDir = project.getFile().getParentFile();

        depSupport = new BazelDependencySupport(workspace, project);

        if ("pom".equals(project.getPackaging())) {
            getLog().warn("Skipping POM project");
        } else {
            writeBazelJson(projectDir);
        }

        try {
            File urlResponseFileTmp = File.createTempFile(urlResponseFile.getName()+".", null, urlResponseFile.getParentFile());
            try {
                synchronized(urlResponseCache) {
                    if(--urlResponseCacheRefcount == 0) {
                        writeUrlResponseCache(urlResponseFileTmp);
                        if(!urlResponseFileTmp.renameTo(urlResponseFile)) {
                            throw new IOException(urlResponseFile.getPath());
                        }
                    }
                }
            } finally {
                urlResponseFileTmp.delete();
            }
        } catch (IOException e) {
            throw new MojoExecutionException("failed to write cache file", e);
        }
    }

    Map<String, Integer> readUrlResponseCache(File cacheFile) throws MojoExecutionException {
        Map<String, Integer> cache = new HashMap<>();
        if (cacheFile.exists()) {
            Type type = new TypeToken<Map<String, Integer>>(){}.getType();
            try (FileReader reader = new FileReader(cacheFile)) {
                Map<String, Integer> json = new Gson().fromJson(reader, type);
                if(json != null) {
                    cache = json;
                }
            } catch (IOException | JsonSyntaxException e) {
                cacheFile.renameTo(new File(cacheFile.getAbsolutePath() + ".corrupt"));
                throw new MojoExecutionException("Couldn't read " + cacheFile, e);
            }
        }
        return cache;
    }

    void writeUrlResponseCache(File cacheFile) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        cacheFile.getParentFile().mkdirs();
        try (FileWriter stream = new FileWriter(cacheFile)) {
            gson.toJson(urlResponseCache, stream);
        }
    }

    void writeBazelJson(File projectDir) throws MojoExecutionException {
        File targetDir = new File(projectDir, "target");
        targetDir.mkdirs();
        String bazelJson = targetDir.getAbsolutePath() + "/bazel.json";
        try (FileWriter jsonOut = new FileWriter(bazelJson)) {
            jsonOut.write(new BazelJson(this, depSupport).toJson());
            jsonOut.write("\n");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed writing " + bazelJson, e);
        }
    }

    class BazelJson {
        // Project fields
        String groupId;
        String artifactId;
        String version;
        String packaging;
        String url;

        // BazelGenerator fields
        boolean skip;

        List<ExtraRule> generatedSourceRules;
        List<ExtraRule> extraRules;
        List<Artifact> toolDependencies;

        // dependencies (overkill? should trim this when we know what's useful)
        Map<String, String> repoUrls;
        List<String> compileDeps;
        List<String> providedDeps;
        List<String> testDeps;
        List<Artifact> directDeps;
        List<Artifact> transitiveDeps;

        Object extraConfig;

        BazelJson(BazelGenerator generator, BazelDependencySupport deps) throws MojoExecutionException {
            this.groupId = generator.project.getGroupId();
            this.artifactId = generator.project.getArtifactId();
            this.version = generator.project.getVersion();
            this.packaging = generator.project.getPackaging();
            this.url = generator.project.getUrl();

            this.skip = generator.skip;

            this.repoUrls = getRepoBaseUrls();
            this.compileDeps = deps.getDeps();
            this.providedDeps = deps.getProvidedDeps();
            this.testDeps = deps.getTestDeps();

            this.directDeps = deps.getDirectDepArtifacts();
            this.transitiveDeps = deps.getTransitiveDepArtifacts();

            this.generatedSourceRules = generator.generatedSourceRules;
            this.extraRules = generator.extraRules;
            this.toolDependencies = toolDependencyArtifacts(generator);

            if(generator.extraConfig != null) {
                this.extraConfig = new GsonBuilder().create().fromJson(generator.extraConfig, Object.class);
            }
        }

        Map<String, String> getRepoBaseUrls() {
            return project.getRemoteArtifactRepositories().stream()
                    .collect(Collectors.toMap(ArtifactRepository::getId, ArtifactRepository::getUrl));
        }

        List<Artifact> toolDependencyArtifacts(BazelGenerator generator) {
            return generator.toolDependencies.stream()
                    .map(d -> depSupport.resolveArtifactRepo(repositorySystem.createDependencyArtifact(d)))
                    .collect(Collectors.toList());
        }

        String toJson() {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            return gson.toJson(this);
        }
    }
}
