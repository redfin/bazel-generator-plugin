package com.redfin.maven.plugins.bazelgenerator;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;

public class BazelDependencySupport {

	private final BazelWorkspace workspace;

	// storing map of deps in a LinkedHashMultimap to preserve their order
	private final LinkedHashMultimap<String, String> deps;
	private final List<Artifact> directDepArtifacts;
	private final List<Artifact> transitiveDepArtifacts;

	@SuppressWarnings("deprecation")
	public BazelDependencySupport(BazelWorkspace workspace, MavenProject project) throws MojoExecutionException {
		this.workspace = workspace;

		deps = LinkedHashMultimap.create();
		directDepArtifacts = new ArrayList<>();
		transitiveDepArtifacts = new ArrayList<>();

		for (Artifact dependency : project.getArtifacts()) {
			transitiveDepArtifacts.add(resolveArtifactRepo(dependency));

			String scope = dependency.getScope();
			if (!"system".equals(scope)) {
				addToScope(dependency, deps.get(scope));
			}
		}

		for (Artifact dependency : project.getDependencyArtifacts()) {
			directDepArtifacts.add(resolveArtifactRepo(dependency));
		}
	}

	// abuse the "downloadUrl" field to hold the repo url for json export
	Artifact resolveArtifactRepo(Artifact artifact) {
		try {
			artifact.setDownloadUrl(workspace.getRepoUrl(artifact));
		} catch (MojoExecutionException e) {
			throw new RuntimeException(e);
		}
		return artifact;
	}

	public List<Artifact> getDirectDepArtifacts() {
		return directDepArtifacts;
	}

	public List<Artifact> getTransitiveDepArtifacts() {
		return transitiveDepArtifacts;
	}

	public List<String> getDeps() throws MojoExecutionException {
		return Lists.newArrayList(deps.get("compile"));
	}

	public List<String> getProvidedDeps() throws MojoExecutionException {
		return Lists.newArrayList(deps.get("provided"));
	}

	public List<String> getTestDeps() throws MojoExecutionException {
		return Lists.newArrayList(deps.get("test"));
	}

	private void addToScope(Artifact dependency, Set<String> scopeDeps) throws MojoExecutionException {
		if ("jar".equals(dependency.getType())) {
			String depString = "@" + BazelWorkspace.dependencyWorkspaceName(dependency) + "//jar";
			scopeDeps.add(depString);
		}
	}
}
