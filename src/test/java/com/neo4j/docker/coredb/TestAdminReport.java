package com.neo4j.docker.coredb;

import com.neo4j.docker.utils.DatabaseIO;
import com.neo4j.docker.utils.Neo4jVersion;
import com.neo4j.docker.utils.SetContainerUser;
import com.neo4j.docker.utils.StartupDetector;
import com.neo4j.docker.utils.TemporaryFolderManager;
import com.neo4j.docker.utils.TestSettings;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;


public class TestAdminReport
{
    private static final Logger log = LoggerFactory.getLogger( TestAdminReport.class );
    private final String PASSWORD = "supersecretpassword";
    @RegisterExtension
    public static TemporaryFolderManager temporaryFolderManager = new TemporaryFolderManager();
    private static String reportDestinationFlag;
    private String outputFolderNamePrefix;

    @BeforeAll
    static void setCorrectPathFlagForVersion()
    {
        if( TestSettings.NEO4J_VERSION.isOlderThan( Neo4jVersion.NEO4J_VERSION_500 ) )
        {
            reportDestinationFlag = "--to";
        }
        else
        {
            reportDestinationFlag = "--to-path";
        }
    }

    @BeforeEach
    void getTestName( TestInfo info )
    {
        outputFolderNamePrefix = info.getTestClass().get().getName() + "_" +
                                 info.getTestMethod().get().getName();
        if(!info.getDisplayName().startsWith( info.getTestMethod().get().getName() ))
        {
            outputFolderNamePrefix += "_" + info.getDisplayName() + "-";
        }
        else
        {
            outputFolderNamePrefix += "-";
        }
    }

    private GenericContainer createNeo4jContainer( boolean asCurrentUser)
    {
        GenericContainer container = new GenericContainer( TestSettings.IMAGE_ID )
                .withEnv( "NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes" )
                .withEnv( "NEO4J_AUTH", "neo4j/"+PASSWORD )
                .withExposedPorts( 7474, 7687 )
                .withLogConsumer( new Slf4jLogConsumer( log ) );
        StartupDetector.makeContainerWaitForNeo4jReady( container, PASSWORD );
        if(asCurrentUser)
        {
            SetContainerUser.nonRootUser( container );
        }
        return container;
    }

    @ParameterizedTest(name = "ascurrentuser_{0}")
    @ValueSource(booleans = {true, false})
    void testMountToTmpReports(boolean asCurrentUser) throws Exception
    {
        Path testdir = temporaryFolderManager.createTempFolder( outputFolderNamePrefix );
        try(GenericContainer container = createNeo4jContainer(asCurrentUser))
        {
            temporaryFolderManager.createTempFolderAndMountAsVolume( container,"logs","/logs", testdir);
            Path reportFolder = temporaryFolderManager.createTempFolderAndMountAsVolume( container,
                                                                                         "reports",
                                                                                         "/tmp/reports",
                                                                                         testdir);
            container.start();
            DatabaseIO dbio = new DatabaseIO( container );
            dbio.putInitialDataIntoContainer( "neo4j", PASSWORD );

            Container.ExecResult execResult = container.execInContainer( "neo4j-admin-report" );
            verifyCreatesReport( reportFolder, execResult );
        }
    }

    @ParameterizedTest(name = "ascurrentuser_{0}")
    @ValueSource(booleans = {true, false})
    void testCanWriteReportToAnyMountedLocation_toPathWithEquals(boolean asCurrentUser) throws Exception
    {
        String reportFolderName = "reportAnywhere-"+ (asCurrentUser? "currentuser-":"defaultuser-") + "withEqualsArg-";
        verifyCanWriteToMountedLocation( asCurrentUser,
                                         reportFolderName,
                                         new String[]{"neo4j-admin-report", "--verbose", reportDestinationFlag+"=/reports"} );
    }

    @ParameterizedTest(name = "ascurrentuser_{0}")
    @ValueSource(booleans = {true, false})
    void testCanWriteReportToAnyMountedLocation_toPathWithSpace(boolean asCurrentUser) throws Exception
    {
        String reportFolderName = "reportAnywhere-"+ (asCurrentUser? "currentuser-":"defaultuser-") + "withSpaceArg-";
        verifyCanWriteToMountedLocation( asCurrentUser,
                                         reportFolderName,
                                         new String[]{"neo4j-admin-report", "--verbose", reportDestinationFlag, "/reports"} );
    }

    private void verifyCanWriteToMountedLocation(boolean asCurrentUser, String testFolderPrefix, String[] execArgs) throws Exception
    {
        Path testdir = temporaryFolderManager.createTempFolder( testFolderPrefix );
        try(GenericContainer container = createNeo4jContainer(asCurrentUser))
        {
            temporaryFolderManager.createTempFolderAndMountAsVolume( container, "logs-", "/logs", testdir );
            Path reportFolder = temporaryFolderManager.createTempFolderAndMountAsVolume( container,
                                                                                         "reports-",
                                                                                         "/reports",
                                                                                         testdir );
            container.start();
            DatabaseIO dbio = new DatabaseIO( container );
            dbio.putInitialDataIntoContainer( "neo4j", PASSWORD );
            Container.ExecResult execResult = container.execInContainer(execArgs);
            // log exec results, because the results of an exec don't get logged automatically.
            log.info( execResult.getStdout() );
            log.warn( execResult.getStderr() );
            verifyCreatesReport( reportFolder, execResult );
        }
    }

    @Test
    void shouldErrorIfUserCannotWrite() throws Exception
    {
        try(GenericContainer container = createNeo4jContainer(true))
        {
            Path reportFolder = temporaryFolderManager.createTempFolderAndMountAsVolume(container,
                                                                                        outputFolderNamePrefix,
                                                                                        "/reports");
            temporaryFolderManager.setFolderOwnerToNeo4j( reportFolder );
            // now will be running as non root, and try to write to a folder owned by 7474
            container.start();
            Container.ExecResult execResult = container.execInContainer( "neo4j-admin-report", reportDestinationFlag, "/reports" );
            Assertions.assertTrue( execResult.getStderr().contains( "Folder /reports is not accessible for user: " ),
                                   "Did not error about incorrect file permissions" );
        }
    }

    @ParameterizedTest(name = "mountPoint_{0}")
    @ValueSource(strings = {"/tmp/reports", "/reports"})
    void shouldReownMountedReportDestinationIfRootDoesNotOwn(String mountPoint) throws Exception
    {
        try(GenericContainer container = createNeo4jContainer(false))
        {
            Path reportFolder = temporaryFolderManager.createTempFolderAndMountAsVolume(container,
                                                                                        outputFolderNamePrefix,
                                                                                        mountPoint);
            temporaryFolderManager.setFolderOwnerToCurrentUser( reportFolder );
            // now will be running as root, and try to write to a folder owned by 1000
            container.start();
            Container.ExecResult execResult = container.execInContainer( "neo4j-admin-report", reportDestinationFlag, mountPoint );
            Assertions.assertTrue( execResult.getStderr().isEmpty(),
                                   "errors were encountered when trying to reown "+mountPoint+".\n"+execResult.getStderr());
            verifyCreatesReport( reportFolder, execResult );
        }
    }

    @Test
    void shouldShowNeo4jAdminHelpText_whenCMD() throws Exception
    {
        try(GenericContainer container = createNeo4jContainer(false))
        {
            container.withCommand( "neo4j-admin-report", "--help" );
            StartupDetector.makeContainerWaitUntilFinished( container, Duration.ofSeconds( 20 ) );
            container.start();
            verifyHelpText( container.getLogs(OutputFrame.OutputType.STDOUT),
                            container.getLogs(OutputFrame.OutputType.STDERR) );
        }
    }

    @Test
    void shouldShowNeo4jAdminHelpText_whenEXEC() throws Exception
    {
        try(GenericContainer container = createNeo4jContainer(false))
        {
            temporaryFolderManager.createTempFolderAndMountAsVolume( container,
                                                                     outputFolderNamePrefix,
                                                                     "/logs" );
            container.start();
            Container.ExecResult execResult = container.execInContainer( "neo4j-admin-report", "--help" );
            // log exec results, because the results of an exec don't get logged automatically.
            log.info( "STDOUT:\n" + execResult.getStdout() );
            log.warn( "STDERR:\n" + execResult.getStderr() );
            verifyHelpText( execResult.getStdout(), execResult.getStderr() );
        }
    }

    private void verifyCreatesReport( Path reportFolder,Container.ExecResult reportExecOut ) throws Exception
    {
        List<File> reports = Files.list( reportFolder )
                                  .map( Path::toFile )
                                  .filter( file -> ! file.isDirectory() )
                                  .toList();
        if( TestSettings.NEO4J_VERSION.isOlderThan( Neo4jVersion.NEO4J_VERSION_500 ) )
        {
            // for some reason neo4j-admin report prints jvm details to stderr
            String[] lines = reportExecOut.getStderr().split( "\n" );
            Assertions.assertEquals( 1, lines.length,
                                     "There were errors during report generation" );
            Assertions.assertTrue( lines[0].startsWith( "Selecting JVM" ),
                                   "There were unexpected error messages in the neo4j-admin report:\n"+reportExecOut.getStderr() );
        }
        else
        {
            Assertions.assertEquals( "", reportExecOut.getStderr(),
                                     "There were errors during report generation" );
        }
        Assertions.assertEquals( 1, reports.size(), "Expected exactly 1 report to be produced" );
        Assertions.assertFalse( reportExecOut.toString().contains( "No running instance of neo4j was found" ),
                                "neo4j-admin could not locate running neo4j database" );
    }

    private void verifyHelpText(String stdout, String stderr)
    {
        // in 4.4 the help text goes in stderr
        if( TestSettings.NEO4J_VERSION.isOlderThan( Neo4jVersion.NEO4J_VERSION_500 ) )
        {
            Assertions.assertTrue( stderr.contains(
                    "Produces a zip/tar of the most common information needed for remote assessments." ) );
            Assertions.assertTrue( stderr.contains( "USAGE" ) );
            Assertions.assertTrue( stderr.contains( "OPTIONS" ) );
        }
        else
        {
            Assertions.assertTrue( stdout.contains(
                    "Produces a zip/tar of the most common information needed for remote assessments." ) );
            Assertions.assertTrue( stdout.contains( "USAGE" ) );
            Assertions.assertTrue( stdout.contains( "OPTIONS" ) );
            Assertions.assertEquals( "", stderr, "There were errors when trying to get neo4j-admin-report help text" );
        }
    }
}
