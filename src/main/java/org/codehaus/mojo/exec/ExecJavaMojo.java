package org.codehaus.mojo.exec;

/*
 * Copyright 2005-2006 The Codehaus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.artifact.MavenMetadataSource;

import org.apache.maven.model.Dependency; 
import org.apache.maven.model.Exclusion;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter; 
import org.apache.maven.artifact.resolver.filter.ExcludesArtifactFilter;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange; 

import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * Executes the supplied java class in the current VM with the enclosing project's
 * dependencies as classpath.
 *
 * @author <a href="mailto:kaare.nilsen@gmail.com">Kaare Nilsen</a>, <a href="mailto:dsmiley@mitre.org">David Smiley</a>
 * @goal java
 * @requiresDependencyResolution runtime
 * @execute phase="validate"
 */
public class ExecJavaMojo
    extends AbstractExecMojo
{
    /**
     * @component
     */
    private ArtifactResolver artifactResolver;

    /**
     * @component
     */
    private ArtifactFactory artifactFactory;

    /**
     * @component
     */
    private ArtifactMetadataSource metadataSource;

    /**
     * @parameter expression="${localRepository}"
     * @required
     * @readonly
     */
    private ArtifactRepository localRepository;

    /**
     * @parameter expression="${project.remoteArtifactRepositories}"
     */
    private List remoteRepositories;

    /**
     * @component
     */
    private MavenProjectBuilder projectBuilder;

    /**
     * @parameter expression="${plugin.artifacts}"
     * @readonly
     */
    private List pluginDependencies;

    /**
     * The main class to execute.
     *
     * @parameter expression="${exec.mainClass}"
     * @required
     */
    private String mainClass;

    /**
     * The class arguments.
     *
     * @parameter expression="${exec.arguments}"
     */
    private String[] arguments;

    /**
     * A list of system properties to be passed. Note: as the execution is not forked, some system properties
     * required by the JVM cannot be passed here. Use MAVEN_OPTS or the exec:exec instead. See the user guide for more information.
     *
     * @parameter
     */
    private Property[] systemProperties;

    /**
     * Indicates if mojo should be kept running after the mainclass terminates.
     * Usefull for serverlike apps with deamonthreads.
     *
     * @parameter expression="${exec.keepAlive}" default-value="false"
     */
    private boolean keepAlive;

    /**
     * Indicates if the project dependencies should be used when executing
     * the main class.
     *
     * @parameter expression="${exec.includeProjectDependencies}" default-value="true"
     */
    private boolean includeProjectDependencies;

    /**
     * Indicates if this plugin's dependencies should be used when executing
     * the main class.
     * <p/>
     * This is useful when project dependencies are not appropriate.  Using only
     * the plugin dependencies can be particularly useful when the project is
     * not a java project.  For example a mvn project using the csharp plugins
     * only expects to see dotnet libraries as dependencies.
     *
     * @parameter expression="${exec.includePluginDependencies}" default-value="false"
     */
    private boolean includePluginDependencies;

    /**
     * If provided the ExecutableDependency identifies which of the plugin dependencies
     * contains the executable class.  This will have the affect of only including
     * plugin dependencies required by the identified ExecutableDependency.
     * <p/>
     * If includeProjectDependencies is set to <code>true</code>, all of the project dependencies
     * will be included on the executable's classpath.  Whether a particular project
     * dependency is a dependency of the identified ExecutableDependency will be
     * irrelevant to its inclusion in the classpath.
     *
     * @parameter
     * @optional
     */
    private ExecutableDependency executableDependency;

    /**
     * Wether to interrupt/join and possibly stop the daemon threads upon quitting. <br/> If this is <code>false</code>,
     *  nothing is done and the behavior is similar to what happens if the executed class was run directly in the VM.
    * <p>
     * If you need to mimic the exact same behavior of the VM, disable this. In certain cases (in particular if maven is embedded),
     *  you might need to keep this enabled to make sure threads are properly cleaned up.
     * In that case, see <a href="#daemonThreadJoinTimeout"><code>daemonThreadJoinTimeout</code></a> and 
     * <a href="#stopUnresponsiveDaemonThreads"><code>stopUnresponsiveDaemonThreads</code></a> for further tuning.
     * </p>
     * @parameter expression="${exec.cleanupDaemonThreads} default-value="true"
     */
     private boolean cleanupDaemonThreads;

     /**
     * This defines the number of milliseconds to wait for daemon threads to quit following their interruption.<br/>
     * This is only taken into accout if <a href="#cleanupDaemonThreads"><code>cleanupDaemonThreads</code></a> is <code>true</code>.
     * <p>Daemon threads are interrupted once all known threads are daemon threads.
     * A value &lt; 0 means to not timeout, a value of 0 means infinite wait on join. Following a timeout, a warning will be logged.</p>
     * <p>Note: all threads should properly terminate upon interruption but daemon threads may prove problematic:
     *  as the VM does not usualy join on daemon threads, the code may not have been written to handle interruption properly.
     * For example java.util.Timer is known to not handle interruptions in JDK &lt;= 1.6.
     * So it is not possible for us to infinitely wait by default otherwise maven could hang. A  sensible default value has been chosen, 
     * but this default value <i>may change</i> in the future based on user feedback.</p>
     * @parameter expression="${exec.daemonThreadJoinTimeout}" default-value="15000"
     */
    private long daemonThreadJoinTimeout;

    /**
     * Wether to call Thread.stop() following a timing out of waiting for an interrupted thread to finish.
     * This is only taken into accout if <a href="#cleanupDaemonThreads"><code>cleanupDaemonThreads</code></a> is <code>true</code>.
     * If this is <code>false</code>, or if Thread.stop() fails to get the thread to stop, then
     * a warning is logged and Maven will continue on while the affected threads (and related objects in memory)
     * linger on.
     * @parameter expression="${exec.stopUnresponsiveDaemonThreads} default-value="false"
     */
    private boolean stopUnresponsiveDaemonThreads;

    /**
     * Deprecated this is not needed anymore.
     *
     * @parameter expression="${exec.killAfter}" default-value="-1"
     */
    private long killAfter;
        
    private Properties originalSystemProperties;

    /**
     * Execute goal.
     */
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        if ( killAfter != -1 )
        {
            getLog().warn( "Warning: killAfter is now deprecated. Do you need it ? Please comment on MEXEC-6." );
        }

        if ( null == arguments )
        {
            arguments = new String[0];
        }

        if ( getLog().isDebugEnabled() )
        {
            StringBuffer msg = new StringBuffer( "Invoking : " );
            msg.append( mainClass );
            msg.append( ".main(" );
            for ( int i = 0; i < arguments.length; i++ )
            {
                if ( i > 0 )
                {
                    msg.append( ", " );
                }
                msg.append( arguments[i] );
            }
            msg.append( ")" );
            getLog().debug(  msg );
        }

        IsolatedThreadGroup threadGroup = new IsolatedThreadGroup( mainClass /*name*/ );
        Thread bootstrapThread = new Thread( threadGroup, new Runnable()
        {
            public void run()
            {
                try
                {
                    Method main = Thread.currentThread().getContextClassLoader().loadClass( mainClass )
                        .getMethod( "main", new Class[]{ String[].class } );
                    if ( ! main.isAccessible() )
                    {
                        getLog().debug( "Setting accessibility to true in order to invoke main()." );
                        main.setAccessible(true);
                    }
                    main.invoke( main, new Object[]{arguments} );
                }
                catch ( Exception e )
                {   // just pass it on
                    Thread.currentThread().getThreadGroup().uncaughtException( Thread.currentThread(), e );
                }
            }
        }, mainClass + ".main()" );
        bootstrapThread.setContextClassLoader( getClassLoader() );
        setSystemProperties();

        bootstrapThread.start();
        joinNonDaemonThreads( threadGroup );
        // It's plausible that spontaneously a non-daemon thread might be created as we try and shut down,
        // but it's too late since the termination condition (only daemon threads) has been triggered.
        if ( keepAlive )
        {
            getLog().warn(
                "Warning: keepAlive is now deprecated and obsolete. Do you need it? Please comment on MEXEC-6." );
            waitFor( 0 );
        }

        if ( cleanupDaemonThreads ) {
        
            terminateThreads( threadGroup );
            
            try
            {
                threadGroup.destroy();
            }
            catch (IllegalThreadStateException e)
            {
                getLog().warn( "Couldn't destroy threadgroup " + threadGroup, e );
            }
        }
        

        if ( originalSystemProperties != null )
        {
            System.setProperties( originalSystemProperties );
        }

        synchronized (threadGroup)
        {
            if ( threadGroup.uncaughtException != null )
            {
                throw new MojoExecutionException( null, threadGroup.uncaughtException );
            }
        }

        registerSourceRoots();
    }

    class IsolatedThreadGroup extends ThreadGroup
    {
        Throwable uncaughtException; //synchronize access to this

        public IsolatedThreadGroup( String name )
        {
            super( name );
        }

        public void uncaughtException( Thread thread, Throwable throwable )
        {
            if ( throwable instanceof ThreadDeath )
            {
                return; //harmless
            }
            boolean doLog = false;
            synchronized ( this )
            {
                if ( uncaughtException == null ) // only remember the first one
                {
                    uncaughtException = throwable; // will be reported eventually
                }
                else
                {
                    doLog = true;
                }
            }
            if ( doLog )
            {
                getLog().warn( "an additional exception was thrown", throwable );
            }
        }
    }

    private void joinNonDaemonThreads( ThreadGroup threadGroup )
    {
        boolean foundNonDaemon;
        do {
            foundNonDaemon = false;
            Collection threads = getActiveThreads( threadGroup );
            for ( Iterator iter = threads.iterator(); iter.hasNext(); )
            {
                Thread thread = (Thread) iter.next();
                if ( thread.isDaemon() )
                {
                    continue;
                }
                foundNonDaemon = true;//try again; maybe more threads were created while we were busy
                joinThread( thread, 0 );
            }
        } while (foundNonDaemon);
    }

    private void joinThread( Thread thread, long timeout_msecs )
    {
        try
        {
            getLog().debug( "joining on thread " + thread );
            thread.join( timeout_msecs );
        }
        catch ( InterruptedException e )
        {
            Thread.currentThread().interrupt();//good practice if don't throw
            getLog().warn( "interrupted while joining against thread " + thread, e );//not expected!
        }
        if ( thread.isAlive() ) //generally abnormal
        {
            getLog().warn( "thread " + thread + " was interrupted but is still alive after waiting at least "
                + timeout_msecs + "msecs" );
        }
    }

    private void terminateThreads( ThreadGroup threadGroup )
    {
        // interrupt all threads we know about as of this instant
        Collection threads = getActiveThreads( threadGroup );
        for ( Iterator iter = threads.iterator(); iter.hasNext(); )
        {
            Thread thread = (Thread) iter.next();
            getLog().debug( "interrupting thread " + thread );
            thread.interrupt(); // harmless if for some reason already interrupted or if suddenly not alive.
        }

        long startTime = System.currentTimeMillis();
        Set uncooperativeThreads = new HashSet( threads.size() ); // these were not responsive to interruption
        for ( ; !threads.isEmpty();
              threads = getActiveThreads( threadGroup ), threads.removeAll( uncooperativeThreads ) )
        {
            for ( Iterator iter = threads.iterator(); iter.hasNext(); )
            {
                Thread thread = (Thread) iter.next();
                if ( ! thread.isAlive() )
                {
                    continue;
                }
                if ( ! thread.isInterrupted() )
                {
                    // for uncooperative threads, this might be the 2nd time, but who cares.
                    getLog().debug( "interrupting thread " + thread );
                    thread.interrupt();
                }
                if ( daemonThreadJoinTimeout <= 0 )
                {
                    joinThread( thread, 0 );
                    continue;
                }
                long timeout = daemonThreadJoinTimeout 
                               - ( System.currentTimeMillis() - startTime );
                if ( timeout > 0 )
                {
                    joinThread( thread, timeout );
                }
                if ( ! thread.isAlive() )
                {
                    continue;
                }
                uncooperativeThreads.add( thread ); // ensure we don't process again
                if ( stopUnresponsiveDaemonThreads )
                {
                    getLog().warn( "thread " + thread + " will be Thread.stop()'ed" );
                    thread.stop();
                }
                else
                {
                    getLog().warn( "thread " + thread + " will linger despite being asked to die via interruption" );
                }
            }
        }
        if ( ! uncooperativeThreads.isEmpty() )
        {
            getLog().warn( "NOTE: " + uncooperativeThreads.size() + " thread(s) did not finish despite being asked to "
                + " via interruption. This is not a problem with exec:java, it is a problem with the running code."
                + " Although not serious, it should be remedied.");
        }
        else
        {
            int activeCount = threadGroup.activeCount();
            if ( activeCount != 0 )
            {
                // TODO this may be nothing; continue on anyway; perhaps don't even log in future
                Thread[] threadsArray = new Thread[1];
                threadGroup.enumerate( threadsArray );
                getLog().debug( "strange; " + activeCount
                        + " thread(s) still active in the group " + threadGroup +" such as " + threadsArray[0] );
            }
        }
    }

    private Collection getActiveThreads( ThreadGroup threadGroup )
    {
        Thread[] threads = new Thread[ threadGroup.activeCount() ];
        int numThreads = threadGroup.enumerate( threads );
        Collection result = new ArrayList( numThreads );
        for ( int i = 0; i < threads.length && threads[i] != null; i++ )
        {
            result.add( threads[i] );
        }
        return result;//note: result should be modifiable
    }

    /**
     * Pass any given system properties to the java system properties.
     */
    private void setSystemProperties()
    {
        if ( systemProperties != null )
        {
            originalSystemProperties = System.getProperties();
            for ( int i = 0; i < systemProperties.length; i++ )
            {
                Property systemProperty = systemProperties[i];
                String value = systemProperty.getValue();
                System.setProperty( systemProperty.getKey(), value == null ? "" : value );
            }
        }
    }

    /**
     * Set up a classloader for the execution of the
     * main class.
     *
     * @return
     * @throws MojoExecutionException
     */
    private ClassLoader getClassLoader()
        throws MojoExecutionException
    {
        List classpathURLs = new ArrayList();
        this.addRelevantPluginDependenciesToClasspath( classpathURLs );
        this.addRelevantProjectDependenciesToClasspath( classpathURLs );
        return new URLClassLoader((URL[]) classpathURLs.toArray( new URL[ classpathURLs.size() ] )/*,
                ClassLoader.getSystemClassLoader()*/);
    }

    /**
     * Add any relevant project dependencies to the classpath.
     * Indirectly takes includePluginDependencies and ExecutableDependency into consideration.
     *
     * @param path classpath of {@link java.net.URL} objects
     * @throws MojoExecutionException
     */
    private void addRelevantPluginDependenciesToClasspath( List path )
        throws MojoExecutionException
    {
        if ( hasCommandlineArgs() )
        {
            arguments = parseCommandlineArgs();
        }

        try
        {
            Iterator iter = this.determineRelevantPluginDependencies().iterator();
            while ( iter.hasNext() )
            {
                Artifact classPathElement = (Artifact) iter.next();
                getLog().debug(
                    "Adding plugin dependency artifact: " + classPathElement.getArtifactId() + " to classpath" );
                path.add( classPathElement.getFile().toURL() );
            }
        }
        catch ( MalformedURLException e )
        {
            throw new MojoExecutionException( "Error during setting up classpath", e );
        }

    }

    /**
     * Add any relevant project dependencies to the classpath.
     * Takes includeProjectDependencies into consideration.
     *
     * @param path classpath of {@link java.net.URL} objects
     * @throws MojoExecutionException
     */
    private void addRelevantProjectDependenciesToClasspath( List path )
        throws MojoExecutionException
    {
        if ( this.includeProjectDependencies )
        {
            try
            {
                getLog().debug( "Project Dependencies will be included." );

                URL mainClasses = new File( project.getBuild().getOutputDirectory() ).toURL();
                getLog().debug( "Adding to classpath : " + mainClasses );
                path.add( mainClasses );

                URL testClasses = new File( project.getBuild().getTestOutputDirectory() ).toURL();
                getLog().debug( "Adding to classpath : " + testClasses );
                path.add( testClasses );

                Set dependencies = project.getArtifacts();

                // system scope dependencies are not returned by maven 2.0. See MEXEC-17
                dependencies.addAll( getSystemScopeDependencies() );

                Iterator iter = dependencies.iterator();
                while ( iter.hasNext() )
                {
                    Artifact classPathElement = (Artifact) iter.next();
                    getLog().debug(
                        "Adding project dependency artifact: " + classPathElement.getArtifactId() + " to classpath" );
                    path.add( classPathElement.getFile().toURL() );
                }

            }
            catch ( MalformedURLException e )
            {
                throw new MojoExecutionException( "Error during setting up classpath", e );
            }
        }
        else
        {
            getLog().debug( "Project Dependencies will be excluded." );
        }

    }

    private Collection getSystemScopeDependencies() throws MojoExecutionException
    {
        List systemScopeArtifacts = new ArrayList();

        for ( Iterator artifacts = getAllDependencies().iterator(); artifacts.hasNext(); ) 
        {
            Artifact artifact = (Artifact) artifacts.next();

            if ( artifact.getScope().equals( Artifact.SCOPE_SYSTEM ) )
            {
                systemScopeArtifacts.add( artifact );
            }
        }
        return systemScopeArtifacts;
    }

    // generic method to retrieve all the transitive dependencies
    private Collection getAllDependencies() throws MojoExecutionException
    {
        List artifacts = new ArrayList();
            
        for ( Iterator dependencies = project.getDependencies().iterator(); dependencies.hasNext(); ) 
        {
            Dependency dependency = (Dependency) dependencies.next();

            String groupId = dependency.getGroupId();
            String artifactId = dependency.getArtifactId();

            VersionRange versionRange;
            try
            {
                versionRange = VersionRange.createFromVersionSpec( dependency.getVersion() );
            }
            catch ( InvalidVersionSpecificationException e )
            {
                throw new MojoExecutionException( "unable to parse version", e );
            }

            String type = dependency.getType();
            if ( type == null )
            {
                type = "jar"; //$NON-NLS-1$
            }
            String classifier = dependency.getClassifier();
            boolean optional = dependency.isOptional();
            String scope = dependency.getScope();
            if ( scope == null )
            {
                scope = Artifact.SCOPE_COMPILE;
            }

            Artifact art = this.artifactFactory.createDependencyArtifact( groupId, artifactId, versionRange, type, classifier,
                                                             scope, optional );

            if ( scope.equalsIgnoreCase( Artifact.SCOPE_SYSTEM ) )
            {
                art.setFile( new File( dependency.getSystemPath() ) );
            } 

            List exclusions = new ArrayList();
            for ( Iterator j = dependency.getExclusions().iterator(); j.hasNext(); )
            {
                Exclusion e = (Exclusion) j.next();
                exclusions.add( e.getGroupId() + ":" + e.getArtifactId() ); //$NON-NLS-1$
            }

            ArtifactFilter newFilter = new ExcludesArtifactFilter( exclusions );

            art.setDependencyFilter( newFilter );

            artifacts.add( art );
        }

        return artifacts;
    }

    /**
     * Determine all plugin dependencies relevant to the executable.
     * Takes includePlugins, and the executableDependency into consideration.
     *
     * @return a set of Artifact objects.
     *         (Empty set is returned if there are no relevant plugin dependencies.)
     * @throws MojoExecutionException
     */
    private Set determineRelevantPluginDependencies()
        throws MojoExecutionException
    {
        Set relevantDependencies;
        if ( this.includePluginDependencies )
        {
            if ( this.executableDependency == null )
            {
                getLog().debug( "All Plugin Dependencies will be included." );
                relevantDependencies = new HashSet( this.pluginDependencies );
            }
            else
            {
                getLog().debug( "Selected plugin Dependencies will be included." );
                Artifact executableArtifact = this.findExecutableArtifact();
                Artifact executablePomArtifact = this.getExecutablePomArtifact( executableArtifact );
                relevantDependencies = this.resolveExecutableDependencies( executablePomArtifact );
            }
        }
        else
        {
            relevantDependencies = Collections.EMPTY_SET;
            getLog().debug( "Plugin Dependencies will be excluded." );
        }
        return relevantDependencies;
    }

    /**
     * Get the artifact which refers to the POM of the executable artifact.
     *
     * @param executableArtifact this artifact refers to the actual assembly.
     * @return an artifact which refers to the POM of the executable artifact.
     */
    private Artifact getExecutablePomArtifact( Artifact executableArtifact )
    {
        return this.artifactFactory.createBuildArtifact( executableArtifact.getGroupId(),
                                                         executableArtifact.getArtifactId(),
                                                         executableArtifact.getVersion(), "pom" );
    }

    /**
     * Examine the plugin dependencies to find the executable artifact.
     *
     * @return an artifact which refers to the actual executable tool (not a POM)
     * @throws MojoExecutionException
     */
    private Artifact findExecutableArtifact()
        throws MojoExecutionException
    {
        //ILimitedArtifactIdentifier execToolAssembly = this.getExecutableToolAssembly();

        Artifact executableTool = null;
        for ( Iterator iter = this.pluginDependencies.iterator(); iter.hasNext(); )
        {
            Artifact pluginDep = (Artifact) iter.next();
            if ( this.executableDependency.matches( pluginDep ) )
            {
                executableTool = pluginDep;
                break;
            }
        }

        if ( executableTool == null )
        {
            throw new MojoExecutionException(
                "No dependency of the plugin matches the specified executableDependency." +
                    "  Specified executableToolAssembly is: " + executableDependency.toString() );
        }

        return executableTool;
    }

    private Set resolveExecutableDependencies( Artifact executablePomArtifact )
        throws MojoExecutionException
    {

        Set executableDependencies;
        try
        {
            MavenProject executableProject = this.projectBuilder.buildFromRepository( executablePomArtifact,
                                                                                      this.remoteRepositories,
                                                                                      this.localRepository );

            //get all of the dependencies for the executable project
            List dependencies = executableProject.getDependencies();

            //make Artifacts of all the dependencies
            Set dependencyArtifacts =
                MavenMetadataSource.createArtifacts( this.artifactFactory, dependencies, null, null, null );

            //not forgetting the Artifact of the project itself
            dependencyArtifacts.add( executableProject.getArtifact() );

            //resolve all dependencies transitively to obtain a comprehensive list of assemblies
            ArtifactResolutionResult result = artifactResolver.resolveTransitively( dependencyArtifacts,
                                                                                    executablePomArtifact,
                                                                                    Collections.EMPTY_MAP,
                                                                                    this.localRepository,
                                                                                    this.remoteRepositories,
                                                                                    metadataSource, null,
                                                                                    Collections.EMPTY_LIST );
            executableDependencies = result.getArtifacts();

        }
        catch ( Exception ex )
        {
            throw new MojoExecutionException(
                "Encountered problems resolving dependencies of the executable " + "in preparation for its execution.",
                ex );
        }

        return executableDependencies;
    }

    /**
     * Stop program execution for nn millis.
     *
     * @param millis the number of millis-seconds to wait for,
     *               <code>0</code> stops program forever.
     */
    private void waitFor( long millis )
    {
        Object lock = new Object();
        synchronized ( lock )
        {
            try
            {
                lock.wait( millis );
            }
            catch ( InterruptedException e )
            {
                Thread.currentThread().interrupt(); // good practice if don't throw
                getLog().warn( "Spuriously interrupted while waiting for " + millis + "ms", e);
            }
        }
    }

}
