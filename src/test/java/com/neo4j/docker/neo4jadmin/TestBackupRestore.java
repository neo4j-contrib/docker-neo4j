package com.neo4j.docker.neo4jadmin;

import com.neo4j.docker.utils.DatabaseIO;
import com.neo4j.docker.utils.HostFileSystemOperations;
import com.neo4j.docker.utils.Neo4jVersion;
import com.neo4j.docker.utils.SetContainerUser;
import com.neo4j.docker.utils.StartupDetector;
import com.neo4j.docker.utils.TestSettings;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.LogMessageWaitStrategy;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.file.Path;
import java.time.Duration;

public class TestBackupRestore
{
    // with authentication
    // with non-default user
    private static final Logger log = LoggerFactory.getLogger( TestBackupRestore.class );

    @BeforeAll
    static void beforeAll()
    {
        Assumptions.assumeTrue( TestSettings.NEO4J_VERSION.isAtLeastVersion( Neo4jVersion.NEO4J_VERSION_500 ),
                                "These tests only apply to neo4j-admin images of 5.0 and greater");
        Assumptions.assumeTrue( TestSettings.EDITION == TestSettings.Edition.ENTERPRISE,
                                "backup and restore only available in Neo4j Enterprise" );
    }

    private GenericContainer createDBContainer( boolean asDefaultUser, String password )
    {
        String auth = "none";
        if(!password.equalsIgnoreCase("none"))
        {
            auth = "neo4j/"+password;
        }

        GenericContainer container = new GenericContainer( TestSettings.IMAGE_ID );
        container.withEnv( "NEO4J_AUTH", auth )
                 .withEnv( "NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes" )
                 .withEnv( "NEO4J_dbms_backup_enabled", "true" )
                 .withEnv( "NEO4J_dbms_backup_listen__address", "0.0.0.0:6362" )
                 .withExposedPorts( 7474, 7687, 6362 )
                 .withLogConsumer( new Slf4jLogConsumer( log ) );
        StartupDetector.makeContainerWaitForNeo4jReady(container, password, Duration.ofSeconds( 90 ));
        if(!asDefaultUser)
        {
            SetContainerUser.nonRootUser( container );
        }
        return container;
    }

    private GenericContainer createAdminContainer( boolean asDefaultUser )
    {
        GenericContainer container = new GenericContainer( TestSettings.ADMIN_IMAGE_ID );
        container.withEnv( "NEO4J_ACCEPT_LICENSE_AGREEMENT", "yes" )
                 .withLogConsumer( new Slf4jLogConsumer( log ) )
                 .withStartupCheckStrategy( new OneShotStartupCheckStrategy().withTimeout( Duration.ofSeconds( 90 ) ) );
        if(!asDefaultUser)
        {
            SetContainerUser.nonRootUser( container );
        }
        return container;
    }

    @Test
    void shouldBackupAndRestore_defaultUser_noAuth() throws Exception
    {
        testCanBackupAndRestore( true, "none" );
    }
    @Test
    void shouldBackupAndRestore_nonDefaultUser_noAuth() throws Exception
    {
        testCanBackupAndRestore( false, "none" );
    }
    @Test
    void shouldBackupAndRestore_defaultUser_withAuth() throws Exception
    {
        testCanBackupAndRestore( true, "secretpassword" );
    }
    @Test
    void shouldBackupAndRestore_nonDefaultUser_withAuth() throws Exception
    {
        testCanBackupAndRestore( false, "secretpassword" );
    }

    private void testCanBackupAndRestore(boolean asDefaultUser, String password) throws Exception
    {
        final String dbUser = "neo4j";
        Path testOutputFolder = HostFileSystemOperations.createTempFolder( "backupRestore-" );

        // BACKUP
        // start a database and populate data
        GenericContainer neo4j = createDBContainer( asDefaultUser, password );
        Path dataDir = HostFileSystemOperations.createTempFolderAndMountAsVolume(
                neo4j, "data-", "/data", testOutputFolder );
        neo4j.start();
        DatabaseIO dbio = new DatabaseIO( neo4j );
        dbio.putInitialDataIntoContainer( dbUser, password );
        dbio.verifyInitialDataInContainer( dbUser, password );

        // start admin container to initiate backup
        String neoDBAddress = neo4j.getHost()+":"+neo4j.getMappedPort( 6362 );
        GenericContainer adminBackup = createAdminContainer( asDefaultUser )
                .withNetworkMode( "host" )
                .waitingFor( new LogMessageWaitStrategy().withRegEx( "^Backup complete successful.*" ) )
                .withCommand("neo4j-admin",
                        "database",
                        "backup-legacy",
                        "--database=neo4j",
                        "--backup-dir=/backups",
                        "--from=" + neoDBAddress);

        Path backupDir = HostFileSystemOperations.createTempFolderAndMountAsVolume(
                adminBackup, "backup-", "/backups", testOutputFolder );
        adminBackup.start();

        Assertions.assertTrue( neo4j.isRunning(), "neo4j container should still be running" );
        dbio.verifyInitialDataInContainer( dbUser, password );
        adminBackup.stop();

        // RESTORE

        // write more stuff
        dbio.putMoreDataIntoContainer( dbUser, password );
        dbio.verifyMoreDataIntoContainer( dbUser, password, true );

        // do restore
        dbio.runCypherQuery( dbUser, password, "STOP DATABASE neo4j", "system" );
        GenericContainer adminRestore = createAdminContainer( asDefaultUser )
                .waitingFor( new LogMessageWaitStrategy().withRegEx( "^.*restoreStatus=successful.*" ) )
                .withCommand("neo4j-admin",
                        "database",
                        "restore-legacy",
                        "--database=neo4j",
                        "--from=/backups/neo4j",
                        "--force");
        HostFileSystemOperations.mountHostFolderAsVolume( adminRestore, backupDir, "/backups" );
        HostFileSystemOperations.mountHostFolderAsVolume( adminRestore, dataDir, "/data" );
        adminRestore.start();
        dbio.runCypherQuery( dbUser, password, "START DATABASE neo4j", "system" );

        // verify new stuff is missing
        dbio.verifyMoreDataIntoContainer( dbUser, password, false );

        // clean up
        adminRestore.stop();
        neo4j.stop();
    }
}
