package org.apache.maven.shared.test.plugin;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.MalformedURLException;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryFactory;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.LegacySupport;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.ProjectArtifactMetadata;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.MavenSettingsBuilder;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * Tools to access and manage Maven repositories for test builds, including construction of a local
 * repository directory structure.
 *
 * <p>
 * <b>WARNING:</b> Currently, the <code>createLocalRepositoryFromPlugin</code> method will not
 * resolve parent POMs that exist <b>only</b> in your normal local repository, and are not reachable
 * using the relativePath element. This may result in failed test builds, as one or more of the
 * plugin's ancestor POMs cannot be resolved.
 * </p>
 *
 * @author jdcasey
 * @version $Id: RepositoryTool.java 1505991 2013-07-23 11:49:59Z jvanzyl $
 */
@Component( role = RepositoryTool.class )
public class RepositoryTool
    implements Contextualizable
{
    /** Plexus role */
    public static final String ROLE = RepositoryTool.class.getName();

    @Requirement
    private ArtifactRepositoryFactory repositoryFactory;

    @Requirement
    private MavenSettingsBuilder settingsBuilder;

    @Requirement
    private ArtifactFactory artifactFactory;

    @Requirement
    private ArtifactInstaller artifactInstaller;

    @Requirement
    private LegacySupport legacySupport;

    // contextualized.
    private PlexusContainer container;

    /**
     * Lookup and return the location of the normal Maven local repository.
     *
     * @return the location of the normal Maven local repository.
     * @throws TestToolsException if any
     */
    public File findLocalRepositoryDirectory()
        throws TestToolsException
    {
        String localRepo = System.getProperty( "maven.local.repo" );
        if ( StringUtils.isNotEmpty( localRepo ) )
        {
            return new File( localRepo );
        }

        Settings settings;
        try
        {
            DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
            request.setUserSettingsFile( new File( System.getProperty( "user.home" ), ".m2/settings.xml" ) );
            request.setGlobalSettingsFile( new File( System.getProperty( "maven.home" ), "conf/settings.xml" ) );
            settings = settingsBuilder.buildSettings( request );
        }
        catch ( IOException e )
        {
            throw new TestToolsException( "Error building Maven settings.", e );
        }
        catch ( XmlPullParserException e )
        {
            throw new TestToolsException( "Error building Maven settings.", e );
        }

        if ( settings == null || settings.getLocalRepository() == null
            || settings.getLocalRepository().trim().length() < 1 )
        {
            return new File( System.getProperty( "user.home" ), ".m2/repository" );
        }

        return new File( settings.getLocalRepository() );
    }

    /**
     * Construct an ArtifactRepository instance that refers to the normal Maven local repository.
     *
     * @return an ArtifactRepository instance
     * @throws TestToolsException if any
     */
    public ArtifactRepository createLocalArtifactRepositoryInstance()
        throws TestToolsException
    {
        File localRepoDir = findLocalRepositoryDirectory();

        return createLocalArtifactRepositoryInstance( localRepoDir );
    }

    /**
     * Construct an ArtifactRepository instance that refers to the test-time Maven local repository.
     *
     * @param localRepositoryDirectory The location of the local repository to be used for test builds.
     * @return an ArtifactRepository instance
     * @throws TestToolsException if any
     */
    public ArtifactRepository createLocalArtifactRepositoryInstance( File localRepositoryDirectory )
        throws TestToolsException
    {
        ArtifactRepositoryLayout defaultLayout;
        try
        {
            defaultLayout = (ArtifactRepositoryLayout) container.lookup( ArtifactRepositoryLayout.ROLE, "default" );
        }
        catch ( ComponentLookupException e )
        {
            throw new TestToolsException( "Error retrieving default repository layout.", e );
        }

        try
        {
            return repositoryFactory.createArtifactRepository( "local", localRepositoryDirectory.toURL()
                .toExternalForm(), defaultLayout, null, null );
        }
        catch ( MalformedURLException e )
        {
            throw new TestToolsException( "Error converting local repo directory to a URL.", e );
        }
    }

    /**
     * Install a test version of a plugin - along with its POM, and as many ancestor POMs as can be
     * reached using the &lt;relativePath/&gt; element - to a clean local repository directory for
     * use in test builds.
     *
     * <p>
     * <b>WARNING:</b> Currently, this method will not resolve parent POMs that exist <b>only</b> in
     * your normal local repository, and are not reachable using the relativePath element. This may
     * result in failed test builds, as one or more of the plugin's ancestor POMs cannot be resolved.
     * </p>
     *
     * @param project
     * @param realPomFile
     * @param targetLocalRepoBasedir
     * @throws TestToolsException if any
     */
    public void createLocalRepositoryFromComponentProject( MavenProject project, File realPomFile,
                                                           File targetLocalRepoBasedir )
        throws TestToolsException
    {
        Artifact artifact = project.getArtifact();

        if ( "pom".equals( project.getPackaging() ) )
        {
            artifact.setFile( project.getFile() );
        }

        ArtifactRepository localRepository = createLocalArtifactRepositoryInstance( targetLocalRepoBasedir );

        String localPath = localRepository.pathOf( artifact );

        File destination = new File( localRepository.getBasedir(), localPath );
        if ( !destination.getParentFile().exists() )
        {
            destination.getParentFile().mkdirs();
        }

        legacySupport.setSession( new MavenSession( container, MavenRepositorySystemUtils.newSession(),
                                                    new DefaultMavenExecutionRequest(),
                                                    new DefaultMavenExecutionResult() ) );
        try
        {
            artifactInstaller.install( artifact.getFile(), artifact, localRepository );
        }
        catch ( ArtifactInstallationException e )
        {
            throw new TestToolsException( "Error installing plugin artifact to target local repository: "
                + targetLocalRepoBasedir, e );
        }
        finally
        {
            legacySupport.setSession( null );
        }

        installLocallyReachableAncestorPoms( realPomFile, localRepository );
    }

    /**
     * Traverse &lt;relativePath/&gt; links for successive POMs in the plugin's ancestry, installing
     * each one into the test-time local repository.
     *
     * @param realPomFile The real plugin POM; a starting point, but the POM is already installed,
     *   so we won't actually install this file, only use it to locate parents.
     * @param localRepo The test-time local repository instance
     * @throws TestToolsException if any
     */
    private void installLocallyReachableAncestorPoms( File realPomFile, ArtifactRepository localRepo )
        throws TestToolsException
    {
        MavenXpp3Reader pomReader = new MavenXpp3Reader();

        File pom = realPomFile;

        boolean firstPass = true;

        while ( pom != null )
        {

            if ( !pom.exists() )
            {
                pom = null;
                break;
            }

            String pomGroupId = null;
            String pomArtifactId = null;
            String pomVersion = null;

            Reader reader = null;

            File currentPom = pom;

            try
            {
                reader = ReaderFactory.newXmlReader( pom );

                Model model = pomReader.read( reader );

                pomGroupId = model.getGroupId();
                pomArtifactId = model.getArtifactId();
                pomVersion = model.getVersion();

                Parent parent = model.getParent();
                if ( parent != null )
                {
                    pom = new File( pom.getParentFile(), parent.getRelativePath() );

                    if ( pomGroupId == null )
                    {
                        pomGroupId = parent.getGroupId();
                    }

                    if ( pomVersion == null )
                    {
                        pomVersion = parent.getVersion();
                    }
                }
                else
                {
                    pom = null;
                }
            }
            catch ( IOException e )
            {
                throw new TestToolsException( "Error reading ancestor POM: " + currentPom, e );
            }
            catch ( XmlPullParserException e )
            {
                throw new TestToolsException( "Error reading ancestor POM: " + currentPom, e );
            }
            finally
            {
                IOUtil.close( reader );
            }

            if ( !firstPass )
            {
                Artifact pomArtifact = artifactFactory.createProjectArtifact( pomGroupId, pomArtifactId, pomVersion );
                pomArtifact.addMetadata( new ProjectArtifactMetadata( pomArtifact, currentPom ) );

                try
                {
                    artifactInstaller.install( currentPom, pomArtifact, localRepo );
                }
                catch ( ArtifactInstallationException e )
                {
                    throw new TestToolsException( "Error installing ancestor POM: " + currentPom
                        + " to target local repository: " + localRepo.getBasedir(), e );
                }
            }
            else
            {
                firstPass = false;
            }
        }
    }

    /**
     * Retrieve the PlexusContainer instance used to instantiate this component. The container is
     * used to retrieve the default ArtifactRepositoryLayout component, for use in constructing
     * instances of ArtifactRepository that can be used to access local repositories.
     *
     * @see org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable#contextualize(org.codehaus.plexus.context.Context)
     */
    public void contextualize( Context context )
        throws ContextException
    {
        this.container = (PlexusContainer) context.get( PlexusConstants.PLEXUS_KEY );
    }
}
