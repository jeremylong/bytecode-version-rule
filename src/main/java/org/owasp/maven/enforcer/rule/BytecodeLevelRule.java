/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2017 Jeremy Long. All Rights Reserved.
 */
package org.owasp.maven.enforcer.rule;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.ArtifactCoordinate;
import org.apache.maven.shared.artifact.TransferUtils;
import org.apache.maven.shared.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.artifact.resolve.ArtifactResolverException;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;

/**
 * @author Jeremy Long
 */
public class BytecodeLevelRule implements EnforcerRule {

    public final static int JAVA_9 = 53; //(0x35 hex)
    public final static int JAVA_8 = 52; //(0x34 hex)
    public final static int JAVA_7 = 51; //(0x33 hex)
    public final static int JAVA_6 = 50; //(0x32 hex)
    public final static int JAVA_5 = 49; //(0x31 hex)
    public final static int JDK_1_4 = 48; //(0x30 hex)
    public final static int JDK_1_3 = 47; //(0x2F hex)
    public final static int JDK_1_2 = 46; //(0x2E hex)
    public final static int JDK_1_1 = 45; //(0x2D hex)

    private static final int JAVA_CLASS_HEADER = 0xCAFEBABE;
    private int supportedJvmByteCodeLevel = JAVA_7;
    private boolean excludeScopeTest = true;
    private boolean excludeScopeProvided = true;
    private Log log;

    @Override
    public void execute(EnforcerRuleHelper helper) throws EnforcerRuleException {
        log = helper.getLog();
        try {
            MavenProject project = (MavenProject) helper.evaluate("${project}");
            List<MavenProject> reactorProjects = (List<MavenProject>) helper.evaluate("${reactorProjects}");
            MavenSession session = (MavenSession) helper.evaluate("${session}");
            List<ArtifactRepository> remoteRepositories = (List<ArtifactRepository>) helper.evaluate("${project.remoteArtifactRepositories}");
            ArtifactResolver artifactResolver = (ArtifactResolver) helper.getComponent(ArtifactResolver.class);
            DependencyGraphBuilder dependencyGraphBuilder = (DependencyGraphBuilder) helper.getComponent(DependencyGraphBuilder.class); //evaluate("${dependencyGraphBuilder}");            

            Set<DependencyReference> dependencies = getProjectDependencies(project, session, dependencyGraphBuilder, reactorProjects, remoteRepositories, artifactResolver);
            boolean failBuild = false;
            StringBuilder sb = new StringBuilder();

            for (DependencyReference d : dependencies) {
                final boolean result = hasInvalidByteCodeLevel(d);
                failBuild |= result;
                if (result) {
                    sb.append(String.format("%n%s:%s:%s", d.getGroupId(), d.getArtifactId(), d.getVersion()));
                    if (d.getDependencyTrail() != null && !d.getDependencyTrail().isEmpty()) {
                        if (d.getDependencyTrail().size() == 1) {
                            sb.append(String.format("%n - project path: %s", d.getDependencyTrail().get(0)));
                        } else {
                            sb.append(String.format("%n - project paths:"));
                            for (int x = 0; x < d.getDependencyTrail().size(); x++) {
                                sb.append(String.format(" %s,", d.getDependencyTrail().get(x)));
                            }
                            sb.setLength(sb.length() - 1);
                        }
                    }
//                  if (d.getAvailableVersions()!=null && !d.getAvailableVersions().isEmpty()) {
//                      List<ArtifactVersion> versions = d.getAvailableVersions();
//                      Collections.sort(versions);
//                      //TODO only display the max top 5?  some deps have huge lists - go look it up...
//                  }
                }
            }

            if (failBuild) {
                sb.insert(0, "The following dependencies exceed the maximum supported JVM byte code level:");
                throw new EnforcerRuleException(sb.toString());
            }

        } catch (ExpressionEvaluationException e) {
            throw new EnforcerRuleException("Unable to lookup an expression " + e.getLocalizedMessage(), e);
        } catch (ComponentLookupException ex) {
            throw new RuntimeException(ex);
        }
    }

    protected boolean hasInvalidByteCodeLevel(DependencyReference dependency) {

        try (FileInputStream fis = new FileInputStream(dependency.getPath());
                BufferedInputStream bis = new BufferedInputStream(fis);
                JarInputStream jarInput = new JarInputStream(bis);
                DataInputStream in = new DataInputStream(jarInput)) {

            JarEntry entry = jarInput.getNextJarEntry();
            while (entry != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".class")) {
                    int magic = in.readInt();
                    if (magic != JAVA_CLASS_HEADER) {
                        log.debug(String.format("%s contains an invalid class", dependency.toString()));
                    } else {
                        int minor = in.readUnsignedShort();
                        int major = in.readUnsignedShort();
                        if (major > supportedJvmByteCodeLevel) {
                            return true;
                        }
                    }
                }
                entry = jarInput.getNextJarEntry();
            }

        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return false;
    }

    /**
     * Scans the project's artifacts and adds them to the engine's dependency
     * list.
     *
     * @param project the project to scan the dependencies of
     * @param engine the engine to use to scan the dependencies
     * @return a collection of exceptions that may have occurred while resolving
     * and scanning the dependencies
     */
    private Set<DependencyReference> getProjectDependencies(MavenProject project, MavenSession session, DependencyGraphBuilder dependencyGraphBuilder, List<MavenProject> reactorProjects, List<ArtifactRepository> remoteRepositories, ArtifactResolver artifactResolver) throws EnforcerRuleException {
        final Set<DependencyReference> references = new HashSet<>();
        try {
            final DependencyNode dn = dependencyGraphBuilder.buildDependencyGraph(project, null, reactorProjects);
            final ProjectBuildingRequest buildingRequest = newResolveArtifactProjectBuildingRequest(session, remoteRepositories);
            if (collectDependencies(references, project, dn.getChildren(), buildingRequest, artifactResolver)) {
                throw new EnforcerRuleException("Unable to resolve the projects dependencies");
            }
        } catch (DependencyGraphBuilderException ex) {
            final String msg = String.format("Unable to build dependency graph on project %s", project.getName());
            throw new EnforcerRuleException(msg, ex);
        }
        return references;
    }

    /**
     * Resolves the projects artifacts using Aether and scans the resulting
     * dependencies.
     *
     * @param engine the core dependency-check engine
     * @param project the project being scanned
     * @param nodes the list of dependency nodes, generally obtained via the
     * DependencyGraphBuilder
     * @param buildingRequest the Maven project building request
     * @return true if the collection of dependencies failed
     */
    private boolean collectDependencies(Set<DependencyReference> references, MavenProject project, List<DependencyNode> nodes,
            ProjectBuildingRequest buildingRequest, ArtifactResolver artifactResolver) {
        boolean collectionFailed = false;
        for (DependencyNode dependencyNode : nodes) {
            if ((excludeScopeTest
                    && org.apache.maven.artifact.Artifact.SCOPE_TEST.equals(dependencyNode.getArtifact().getScope()))
                    || (excludeScopeProvided
                    && org.apache.maven.artifact.Artifact.SCOPE_PROVIDED.equals(dependencyNode.getArtifact().getScope()))) {
                continue;
            }
            collectionFailed |= collectDependencies(references, project, dependencyNode.getChildren(), buildingRequest, artifactResolver);

            boolean isResolved = false;
            File artifactFile = null;
            String artifactId = null;
            String groupId = null;
            String version = null;
            List<ArtifactVersion> availableVersions = null;
            if (org.apache.maven.artifact.Artifact.SCOPE_SYSTEM.equals(dependencyNode.getArtifact().getScope())) {
                List<Dependency> dependencies = (List<Dependency>) project.getDependencies();
                for (Dependency d : dependencies) {
                    final Artifact a = dependencyNode.getArtifact();
                    if (d.getSystemPath() != null && artifactsMatch(d, a)) {
                        artifactFile = new File(d.getSystemPath());
                        isResolved = artifactFile.isFile();
                        groupId = a.getGroupId();
                        artifactId = a.getArtifactId();
                        version = a.getVersion();
                        availableVersions = a.getAvailableVersions();
                        break;
                    }
                }
                if (!isResolved) {
                    log.error("Unable to resolve system scoped dependency: " + dependencyNode.toNodeString());
                    collectionFailed = true;
                    continue;
                }
            } else {
                final ArtifactCoordinate coordinate = TransferUtils.toArtifactCoordinate(dependencyNode.getArtifact());
                final Artifact result;
                try {
                    result = artifactResolver.resolveArtifact(buildingRequest, coordinate).getArtifact();
                } catch (ArtifactResolverException ex) {
                    log.debug("Collection failed", ex);
                    final String msg = String.format("Error resolving '%s' in project %s",
                            dependencyNode.getArtifact().getId(), project.getName());
                    log.error(msg);
                    collectionFailed = true;
                    continue;
                }
                isResolved = result.isResolved();
                artifactFile = result.getFile();
                groupId = result.getGroupId();
                artifactId = result.getArtifactId();
                version = result.getVersion();
                availableVersions = result.getAvailableVersions();
            }
            if (isResolved && artifactFile != null && artifactFile.isFile()) {
                DependencyReference dep = new DependencyReference(groupId, artifactId, version, artifactFile,
                        availableVersions, dependencyNode.getArtifact().getDependencyTrail());
                references.add(dep);
            } else {
                final String msg = String.format("Unable to resolve '%s' in project %s",
                        dependencyNode.getArtifact().getId(), project.getName());
                log.error(msg);
                collectionFailed = true;
            }

        }
        return collectionFailed;
    }

    /**
     * Determines if the groupId, artifactId, and version of the Maven
     * dependency and artifact match.
     *
     * @param d the Maven dependency
     * @param a the Maven artifact
     * @return true if the groupId, artifactId, and version match
     */
    private static boolean artifactsMatch(org.apache.maven.model.Dependency d, Artifact a) {
        return (isEqualOrNull(a.getArtifactId(), d.getArtifactId()))
                && (isEqualOrNull(a.getGroupId(), d.getGroupId()))
                && (isEqualOrNull(a.getVersion(), d.getVersion()));
    }

    /**
     * Compares two strings for equality; if both strings are null they are
     * considered equal.
     *
     * @param left the first string to compare
     * @param right the second string to compare
     * @return true if the strings are equal or if they are both null; otherwise
     * false.
     */
    private static boolean isEqualOrNull(String left, String right) {
        return (left != null && left.equals(right)) || (left == null && right == null);
    }

    /**
     * @param session
     * @param remoteRepositories
     * @return Returns a new ProjectBuildingRequest populated from the current
     * session and the current project remote repositories, used to resolve
     * artifacts.
     */
    private ProjectBuildingRequest newResolveArtifactProjectBuildingRequest(MavenSession session, List<ArtifactRepository> remoteRepositories) {
        final ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        buildingRequest.setRemoteRepositories(remoteRepositories);
        return buildingRequest;
    }

    @Override
    public boolean isCacheable() {
        return false;
    }

    @Override
    public boolean isResultValid(EnforcerRule cachedRule) {
        return false;
    }

    @Override
    public String getCacheId() {
        return null;
    }

    /**
     * Set the value of supportedJvmByteCodeLevel.
     *
     * @param supportedJvmByteCodeLevel new value of supportedJvmByteCodeLevel
     */
    public void setSupportedJvmByteCodeLevel(int supportedJvmByteCodeLevel) {
        this.supportedJvmByteCodeLevel = supportedJvmByteCodeLevel;
    }

    /**
     * Get the value of supportedJvmByteCodeLevel.
     *
     * @return the value of supportedJvmByteCodeLevel
     */
    public int getSupportedJvmByteCodeLevel() {
        return supportedJvmByteCodeLevel;
    }

    /**
     * Set the value of excludeScopeTest.
     *
     * @param excludeScopeTest new value of excludeScopeTest
     */
    public void setExcludeScopeTest(Boolean excludeScopeTest) {
        this.excludeScopeTest = excludeScopeTest;
    }

    /**
     * Get the value of excludeScopeTest.
     *
     * @return the value of excludeScopeTest
     */
    public Boolean getExcludeScopeTest() {
        return excludeScopeTest;
    }

    /**
     * Get the value of excludeScopeProvided
     *
     * @return the value of excludeScopeProvided
     */
    public boolean isExcludeScopeProvided() {
        return excludeScopeProvided;
    }

    /**
     * Set the value of excludeScopeProvided
     *
     * @param excludeScopeProvided new value of excludeScopeProvided
     */
    public void setExcludeScopeProvided(boolean excludeScopeProvided) {
        this.excludeScopeProvided = excludeScopeProvided;
    }

}
