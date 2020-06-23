package com.neo4j.docker.utils;

import org.junit.jupiter.api.Assertions;
import org.testcontainers.containers.GenericContainer;

import java.util.List;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

public class DatabaseIO
{
    private static Config TEST_DRIVER_CONFIG = Config.builder().withoutEncryption().build();

    private GenericContainer container;
    private String boltUri;

    public DatabaseIO( GenericContainer container )
    {
        this.container = container;
        this.boltUri = getBoltURIFromContainer( container );
    }

    public static String getBoltURIFromContainer( GenericContainer container )
    {
        return "bolt://" + container.getContainerIpAddress() + ":" + container.getMappedPort( 7687 );
    }

    public void createAndDeleteNode( String user, String password )
    {

        try (
                Driver driver = GraphDatabase.driver( boltUri, AuthTokens.basic( user, password ), TEST_DRIVER_CONFIG );
                Session session = driver.session()
        )
        {
            Result rs = session.run( "CREATE (node:createAndDeleteNode {val:1}) RETURN node.val" );
            Assertions.assertEquals( 1, rs.single().get( 0 ).asInt(), "did not receive expected result from cypher CREATE query" );
            rs = session.run( "MATCH (node:createAndDeleteNode {val:1}) WITH node LIMIT 2 DETACH DELETE node" );
            Assertions.assertEquals( 1, rs.consume().counters().nodesDeleted(), "did not receive expected result from cypher DELETE query" );
        }
    }

    public void putInitialDataIntoContainer( String user, String password )
    {
        try (
                Driver driver = GraphDatabase.driver( boltUri, AuthTokens.basic( user, password ), TEST_DRIVER_CONFIG );
                Session session = driver.session()
        )
        {
            Result rs = session.run( "CREATE (arne:dog {name:'Arne'})-[:SNIFFS]->(bosse:dog {name:'Bosse'}) RETURN arne.name" );
            Assertions.assertEquals( "Arne", rs.single().get( 0 ).asString(), "did not receive expected result from cypher CREATE query" );
        }
    }

    public void verifyDataInContainer( String user, String password )
    {
        try (
                Driver driver = GraphDatabase.driver( boltUri, AuthTokens.basic( user, password ), TEST_DRIVER_CONFIG );
                Session session = driver.session()
        )
        {
            Result rs = session.run( "MATCH (a:dog)-[:SNIFFS]->(b:dog) RETURN a.name" );
            Assertions.assertEquals( "Arne", rs.single().get( 0 ).asString(), "did not receive expected result from cypher CREATE query" );
        }
    }

    public void changePassword( String user, String oldPassword, String newPassword )
    {
        if ( TestSettings.NEO4J_VERSION.isAtLeastVersion( Neo4jVersion.NEO4J_VERSION_400 ) )
        {
            try (
                    Driver driver = GraphDatabase.driver( boltUri, AuthTokens.basic( user, oldPassword ), TEST_DRIVER_CONFIG );
                    Session session = driver.session( SessionConfig.forDatabase( "system" ) )
            )
            {
                Result rs = session.run( "ALTER CURRENT USER SET PASSWORD FROM '" + oldPassword + "' TO '" + newPassword + "'" );
            }
        }
        else
        {
            runCypherProcedure( user, oldPassword, "CALL dbms.changePassword('" + newPassword + "')" );
        }
    }

    public List<Record> runCypherProcedure( String user, String password, String cypher )
    {
        try (
                Driver driver = GraphDatabase.driver( boltUri, AuthTokens.basic( user, password ), TEST_DRIVER_CONFIG );
                Session session = driver.session()
        )
        {
            return session.run( cypher ).list();
        }
    }

    public void verifyConnectivity( String user, String password )
    {
        try ( Driver driver = GraphDatabase.driver(
                getBoltURIFromContainer( container ),
                AuthTokens.basic( user, password ),
                TEST_DRIVER_CONFIG ) )
        {
            driver.verifyConnectivity();
        }
    }
}
