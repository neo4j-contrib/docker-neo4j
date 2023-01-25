package com.neo4j.docker.utils;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.shaded.org.apache.commons.io.IOUtils;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

public class TemporaryFolderManager implements AfterAllCallback
{
    private static final Logger log = LoggerFactory.getLogger( TemporaryFolderManager.class );
    // if we ever run parallel tests, random number generator and
    // list of folders to compress need to be made thread safe
    private Random rng = new Random(  );
    private List<Path> toCompressAfterAll = new ArrayList<>();
    private final Path testOutputParentFolder;

    public TemporaryFolderManager( )
    {
        this(TestSettings.TEST_TMP_FOLDER);
    }
    public TemporaryFolderManager( Path testOutputParentFolder )
    {
        this.testOutputParentFolder = testOutputParentFolder;
    }

    @Override
    public void afterAll( ExtensionContext extensionContext ) throws Exception
    {
        log.info( "Performing cleanup of {}", testOutputParentFolder );
        // create tar archive of data
        for(Path p : toCompressAfterAll)
        {
            String tarOutName = p.getFileName().toString() + ".tar.gz";
            try ( OutputStream fo = Files.newOutputStream( p.getParent().resolve( tarOutName ) );
                  OutputStream gzo = new GzipCompressorOutputStream( fo );
                  TarArchiveOutputStream archiver = new TarArchiveOutputStream( gzo ) )
            {
                archiver.setLongFileMode( TarArchiveOutputStream.LONGFILE_POSIX );
                List<Path> files = Files.walk( p ).collect( Collectors.toList());
                for(Path fileToBeArchived : files)
                {
                    // don't archive directories...
                    if(fileToBeArchived.toFile().isDirectory()) continue;
                    try( InputStream fileStream = Files.newInputStream( fileToBeArchived ))
                    {
                        ArchiveEntry entry = archiver.createArchiveEntry( fileToBeArchived, testOutputParentFolder.relativize( fileToBeArchived ).toString() );
                        archiver.putArchiveEntry( entry );
                        IOUtils.copy( fileStream, archiver );
                        archiver.closeArchiveEntry();
                    } catch (IOException ioe)
                    {
                        // consume the error, because sometimes, file permissions won't let us copy
                        log.warn( "Could not archive "+ fileToBeArchived, ioe);
                    }
                }
                archiver.finish();
            }
        }
        // delete original folders
        setFolderOwnerTo( SetContainerUser.getNonRootUserString(),
                          toCompressAfterAll.toArray(new Path[toCompressAfterAll.size()]) );

        if(extensionContext != null) log.info( "Deleting test folders for {}", extensionContext.getDisplayName() );
        for(Path p : toCompressAfterAll)
        {
            log.debug( "Deleting test output folder {}", p.getFileName().toString() );
            FileUtils.deleteDirectory( p.toFile() );
        }
        toCompressAfterAll.clear();
    }

    public Path createTempFolderAndMountAsVolume( GenericContainer container, String hostFolderNamePrefix,
                                                         String containerMountPoint ) throws IOException
	{
		return createTempFolderAndMountAsVolume( container, hostFolderNamePrefix, containerMountPoint,
												 testOutputParentFolder );
	}

    public Path createTempFolderAndMountAsVolume( GenericContainer container, String hostFolderNamePrefix,
														 String containerMountPoint, Path parentFolder ) throws IOException
    {
        Path hostFolder = createTempFolder( hostFolderNamePrefix, parentFolder );
        mountHostFolderAsVolume( container, hostFolder, containerMountPoint );
        return hostFolder;
    }

    public void mountHostFolderAsVolume(GenericContainer container, Path hostFolder, String containerMountPoint)
    {
        container.withFileSystemBind( hostFolder.toAbsolutePath().toString(),
                                      containerMountPoint,
                                      BindMode.READ_WRITE );
    }

    public Path createTempFolder( String folderNamePrefix ) throws IOException
    {
    	return createTempFolder( folderNamePrefix, testOutputParentFolder );
    }

    public Path createTempFolder( String folderNamePrefix, Path parentFolder ) throws IOException
    {
        String randomStr = String.format( "%04d", rng.nextInt(10000 ) );  // random 4 digit number
        Path hostFolder = parentFolder.resolve( folderNamePrefix + randomStr);
        try
        {
            Files.createDirectories( hostFolder );
        }
        catch ( IOException e )
        {
            log.error( "could not create directory: {}", hostFolder.toAbsolutePath().toString() );
            e.printStackTrace();
            throw e;
        }
        log.info( "Created folder {}", hostFolder.toString() );
        if(parentFolder.equals( testOutputParentFolder ))
        {
            toCompressAfterAll.add( hostFolder );
        }
        return hostFolder;
    }

    public void setFolderOwnerToCurrentUser(Path file) throws Exception
    {
        setFolderOwnerTo( SetContainerUser.getNonRootUserString(), file );
    }

    public void setFolderOwnerToNeo4j( Path file) throws Exception
    {
        setFolderOwnerTo( "7474:7474", file );
    }

    private void setFolderOwnerTo(String userAndGroup, Path ...files) throws Exception
    {
        // uses docker privileges to set file owner, since probably the current user is not a sudoer.

        // Using an nginx because it's easy to verify that the image started.
        try(GenericContainer container = new GenericContainer( DockerImageName.parse( "nginx:latest")))
        {
            container.withExposedPorts( 80 )
                     .waitingFor( Wait.forHttp( "/" ).withStartupTimeout( Duration.ofSeconds( 5 ) ) );
            for(Path p : files)
            {
                mountHostFolderAsVolume( container, p, p.toAbsolutePath().toString() );
            }
            container.start();
            for(Path p : files)
            {
                Container.ExecResult x =
                        container.execInContainer( "chown", "-R", userAndGroup,
                                                   p.toAbsolutePath().toString() );
            }
            container.stop();
        }
    }
}
