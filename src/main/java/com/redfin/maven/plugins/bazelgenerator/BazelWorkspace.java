package com.redfin.maven.plugins.bazelgenerator;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

public class BazelWorkspace {
	private final MavenSession session;
	private final MavenProject project;
	final ConcurrentHashMap<String, Integer> urlResponseCache;
	public final Path rootPath;

	BazelWorkspace(
			MavenSession session,
			MavenProject project,
			ConcurrentHashMap<String, Integer> urlResponseCache
	) throws MojoExecutionException {
		this.session = session;
		this.project = project;
		this.urlResponseCache = urlResponseCache;

		File rootDir = session.getTopLevelProject().getFile().getParentFile();
		this.rootPath = Paths.get(rootDir.getAbsolutePath());
	}

	private int getResponseCode(String url) throws MojoExecutionException {
		if (urlResponseCache.containsKey(url)) {
			return urlResponseCache.get(url);
		}
		try {
			HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
			connection.connect();
			int result = connection.getResponseCode();
			urlResponseCache.put(url, result);
			return result;
		} catch (IOException e) {
			throw new MojoExecutionException("Couldn't handle remote repo URL: " + url, e);
		}
	}

	String getRepoUrl(Artifact dependency) throws MojoExecutionException {
		for (ArtifactRepository repo : project.getRemoteArtifactRepositories()) {
			if ("central".equals(repo.getId())) continue;
			String artifactUrl = repo.getUrl() + "/" + dependency.getGroupId().replaceAll("\\.", "/") + "/" + dependency.getArtifactId() + "/" + dependency.getVersion() + "/";
			if (getResponseCode(artifactUrl) == 200) {
				return repo.getUrl();
			}
		}
		return null;
	}

	public static String dependencyWorkspaceName(Artifact dependency) {
		String raw = dependency.getGroupId() + "_" + dependency.getArtifactId() + "_" + dependency.getVersion();
		return raw.replaceAll("[^A-Za-z0-9_]", "_");
	}
}
