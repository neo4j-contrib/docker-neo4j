package com.neo4j.docker.neo4jserver.configurations;

import com.neo4j.docker.utils.HostFileSystemOperations;
import com.neo4j.docker.utils.Neo4jVersion;
import com.neo4j.docker.utils.SetContainerUser;
import com.neo4j.docker.utils.TestSettings;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TestExtendedConf
{
	private static final Logger log = LoggerFactory.getLogger( TestExtendedConf.class );
    private static Path testConfsFolder;
    private static Configuration logRotationConfig;

	@BeforeAll
	static void ensureFeaturePresent()
	{
		Assumptions.assumeTrue( TestSettings.NEO4J_VERSION.isNewerThan( new Neo4jVersion( 4,2,0 ) ),
								"Extended configuration feature not available before 4.2" );
	}

    @BeforeAll
    static void createVersionSpecificConfigurationSettings() {
        testConfsFolder = Configuration.getConfigurationResourcesFolder( TestSettings.NEO4J_VERSION );
        logRotationConfig = Configuration.getConfigurationNameMap()
                                         .get( Setting.LOGS_GC_ROTATION_KEEPNUMBER );
    }

	protected GenericContainer createContainer(String password)
	{
        return new GenericContainer(TestSettings.IMAGE_ID)
				.withEnv("NEO4J_AUTH", password == null || password.isEmpty() ? "none" : "neo4j/" + password)
				.withEnv("NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes")
				.withEnv( "EXTENDED_CONF", "yeppers" )
				.withExposedPorts(7474, 7687)
				.waitingFor( Wait.forHttp( "/" ).forPort( 7474 ).forStatusCode( 200 ) )
				.withLogConsumer(new Slf4jLogConsumer( log ));
    }


	@ParameterizedTest
	@ValueSource(strings = {"", "secretN30"})
	public void shouldStartWithExtendedConf(String password)
	{
        try(GenericContainer container = createContainer(password))
        {
            container.setWaitStrategy( Wait.forHttp( "/" ).forPort( 7474 ).forStatusCode( 200 ) );
            container.start();

            Assertions.assertTrue( container.isRunning() );
			assertPasswordChangedLogIsCorrect( password, container );
		}
	}

	private void assertPasswordChangedLogIsCorrect( String password, GenericContainer container )
	{
		if ( password.isEmpty()) {
			Assertions.assertFalse( container.getLogs( OutputFrame.OutputType.STDOUT)
                                             .contains( "Changed password for user 'neo4j'." ) );
		} else {
			Assertions.assertTrue( container.getLogs( OutputFrame.OutputType.STDOUT)
                                            .contains( "Changed password for user 'neo4j'." ) );
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "secretN30"})
	void testReadsTheExtendedConfFile_defaultUser(String password) throws Exception
	{
		// set up test folders
		Path testOutputFolder = HostFileSystemOperations.createTempFolder( "extendedConfIsRead-" );
		Path confFolder = HostFileSystemOperations.createTempFolder( "conf-", testOutputFolder );
		Path logsFolder = HostFileSystemOperations.createTempFolder( "logs-", testOutputFolder );

		// copy configuration file and set permissions
		Path confFile = testConfsFolder.resolve( "ExtendedConf.conf" );
		Files.copy( confFile, confFolder.resolve( "neo4j.conf" ) );
		HostFileSystemOperations.setFileOwnerToNeo4j( confFolder.resolve( "neo4j.conf" ) );
		chmodConfFilePermissions( confFolder.resolve( "neo4j.conf" ) );

		// start  container
		try(GenericContainer container = createContainer(password))
		{
			runContainerAndVerify( container, confFolder, logsFolder, password );
		}
	}

    @ParameterizedTest
    @ValueSource( strings = {"", "secretN30"} )
    void testInvalidExtendedConfFile_nonRootUser( String password ) throws Exception
    {
        // set up test folders
        Path testOutputFolder = HostFileSystemOperations.createTempFolder( "extendedConfIsRead-" );
        Path confFolder = HostFileSystemOperations.createTempFolder( "conf-", testOutputFolder );

        // copy configuration file and set permissions
        Path confFile = testConfsFolder.resolve( "InvalidExtendedConf.conf" );
        Files.copy( confFile, confFolder.resolve( "neo4j.conf" ) );
        chmodConfFilePermissions( confFolder.resolve( "neo4j.conf" ) );

        try(GenericContainer container = createContainer( password ))
        {
            SetContainerUser.nonRootUser( container );
            container.withFileSystemBind( "/etc/passwd", "/etc/passwd", BindMode.READ_ONLY );
            container.withFileSystemBind( "/etc/group", "/etc/group", BindMode.READ_ONLY );
            HostFileSystemOperations.mountHostFolderAsVolume( container, confFolder, "/conf" );
            container.setStartupCheckStrategy( new OneShotStartupCheckStrategy().withTimeout( Duration.ofSeconds( 30 ) ) );
            container.setWaitStrategy(
                    Wait.forLogMessage( ".*this is an error message from inside neo4j config command expansion.*", 1 )
                        .withStartupTimeout( Duration.ofSeconds( 30 ) ) );

            Assert.assertThrows( "Container should have errored on start",
                                 ContainerLaunchException.class,
                                 () -> container.start() );

            String logs = container.getLogs();
            // check that error messages from neo4j are visible in docker logs
            Assertions.assertTrue( logs.contains( "Error evaluating value for setting '" + logRotationConfig.name + "'" ) );
            // check that error messages from the command that failed are visible in docker logs
            Assertions.assertTrue( logs.contains( "this is an error message from inside neo4j config command expansion" ) );
            // check that the error is only encountered once (i.e. we quit the docker entrypoint the first time it was encountered)
            Assertions.assertEquals( 1, countOccurrences( Pattern.compile( "Error evaluating value for setting" ), logs ) );
        }
    }

	private int countOccurrences( Pattern pattern, String inString )
	{
		Matcher matcher = pattern.matcher( inString );
		int count = 0;
		while ( matcher.find() )
		{
			count = count + 1;
		}
		return count;
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "secretN30"})
	void testReadsTheExtendedConfFile_nonRootUser(String password) throws Exception
	{
		// set up test folders
		Path testOutputFolder = HostFileSystemOperations.createTempFolder( "extendedConfIsRead-" );
		Path confFolder = HostFileSystemOperations.createTempFolder( "conf-", testOutputFolder );
		Path logsFolder = HostFileSystemOperations.createTempFolder( "logs-", testOutputFolder );

		// copy configuration file and set permissions
		Path confFile = testConfsFolder.resolve( "ExtendedConf.conf" );
		Files.copy( confFile, confFolder.resolve( "neo4j.conf" ) );
		chmodConfFilePermissions( confFolder.resolve( "neo4j.conf" ) );

		try(GenericContainer container = createContainer(password))
		{
			SetContainerUser.nonRootUser( container );
			container.withFileSystemBind( "/etc/passwd", "/etc/passwd", BindMode.READ_ONLY );
			container.withFileSystemBind( "/etc/group", "/etc/group", BindMode.READ_ONLY );
			runContainerAndVerify( container, confFolder, logsFolder, password );
		}
	}

	private void runContainerAndVerify(GenericContainer container, Path confFolder, Path logsFolder, String password) throws Exception
	{
		HostFileSystemOperations.mountHostFolderAsVolume( container, confFolder, "/conf" );
		HostFileSystemOperations.mountHostFolderAsVolume( container, logsFolder, "/logs" );

		container.start();

		Path debugLog = logsFolder.resolve("debug.log");
		Assert.assertTrue("Did not write debug log", debugLog.toFile().exists());

		//Check if the container reads the conf file
		Stream<String> lines = Files.lines( debugLog);
		Optional<String> isMatch = lines.filter( s -> s.contains(logRotationConfig.name + "=20")).findFirst();
		lines.close();
		Assertions.assertTrue(  isMatch.isPresent(), logRotationConfig.name+" was not set correctly");

		//Check the password was changed if set
		assertPasswordChangedLogIsCorrect( password, container );
	}

	private void chmodConfFilePermissions( Path file ) throws IOException
	{

		HashSet<PosixFilePermission> permissions = new HashSet<PosixFilePermission>()
		{{
			add( PosixFilePermission.OWNER_READ );
			add( PosixFilePermission.OWNER_WRITE );
		}};

		if ( TestSettings.NEO4J_VERSION.isAtLeastVersion( new Neo4jVersion( 4, 3, 0 ) ) )
		{
			permissions.add( PosixFilePermission.GROUP_READ );
		}
		Files.setPosixFilePermissions( file, permissions );
	}
}
