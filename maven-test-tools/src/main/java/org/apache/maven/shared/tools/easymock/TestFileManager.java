package org.apache.maven.shared.tools.easymock;

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
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import junit.framework.Assert;

import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;

/**
 * @version $Id: TestFileManager.java 1345444 2012-06-02 05:36:05Z hboutemy $
 */
public class TestFileManager
{
    /** Temp dir from "java.io.tmpdir" property */
    public static final String TEMP_DIR_PATH = System.getProperty( "java.io.tmpdir" );

    private List<File> filesToDelete = new ArrayList<File>();

    private final String baseFilename;

    private final String fileSuffix;

    private StackTraceElement callerInfo;

    private Thread cleanupWarning;

    private boolean warnAboutCleanup = false;

    /**
     * Default constructor
     *
     * @param baseFilename
     * @param fileSuffix
     */
    public TestFileManager( String baseFilename, String fileSuffix )
    {
        this.baseFilename = baseFilename;
        this.fileSuffix = fileSuffix;

        initializeCleanupMonitoring();
    }

    private void initializeCleanupMonitoring()
    {
        callerInfo = new NullPointerException().getStackTrace()[2];

        Runnable warning = new Runnable()
        {
            /** {@inheritDoc} */
            public void run()
            {
                maybeWarnAboutCleanUp();
            }

        };

        cleanupWarning = new Thread( warning );

        Runtime.getRuntime().addShutdownHook( cleanupWarning );
    }

    protected void maybeWarnAboutCleanUp()
    {
        if ( warnAboutCleanup )
        {
            System.out.println( "[WARNING] TestFileManager from: " + callerInfo.getClassName() + " not cleaned up!" );
        }
    }

    /**
     * @param toDelete
     */
    public void markForDeletion( File toDelete )
    {
        filesToDelete.add( toDelete );
        warnAboutCleanup = true;
    }

    /**
     * @return a temp dir
     */
    public synchronized File createTempDir()
    {
        try
        {
            Thread.sleep( 20 );
        }
        catch ( InterruptedException e )
        {
            // ignored
        }

        File dir = new File( TEMP_DIR_PATH, baseFilename + System.currentTimeMillis() );

        dir.mkdirs();
        markForDeletion( dir );

        return dir;
    }

    /**
     * @return a temp file
     * @throws IOException if any
     * @todo maybe use {@link FileUtils#createTempFile(String, String, File)}
     */
    public synchronized File createTempFile()
        throws IOException
    {
        File tempFile = File.createTempFile( baseFilename, fileSuffix );
        tempFile.deleteOnExit();
        markForDeletion( tempFile );

        return tempFile;
    }

    /**
     * @throws IOException if any
     */
    public void cleanUp()
        throws IOException
    {
        for ( Iterator<File> it = filesToDelete.iterator(); it.hasNext(); )
        {
            File file = it.next();

            if ( file.exists() )
            {
                if ( file.isDirectory() )
                {
                    FileUtils.deleteDirectory( file );
                }
                else
                {
                    file.delete();
                }
            }

            it.remove();
        }

        warnAboutCleanup = false;
    }

    /**
     * @param dir
     * @param filename
     * @param shouldExist
     */
    public void assertFileExistence( File dir, String filename, boolean shouldExist )
    {
        File file = new File( dir, filename );

        if ( shouldExist )
        {
            Assert.assertTrue( file.exists() );
        }
        else
        {
            Assert.assertFalse( file.exists() );
        }
    }

    /**
     * @param dir
     * @param filename
     * @param contentsTest
     * @throws IOException if any
     */
    public void assertFileContents( File dir, String filename, String contentsTest )
        throws IOException
    {
        assertFileExistence( dir, filename, true );

        File file = new File( dir, filename );

        FileReader reader = null;
        StringWriter writer = new StringWriter();

        try
        {
            reader = new FileReader( file );

            IOUtil.copy( reader, writer );
        }
        finally
        {
            IOUtil.close( reader );
        }

        Assert.assertEquals( contentsTest, writer.toString() );
    }

    /**
     * @param dir
     * @param filename
     * @param contents
     * @return
     * @throws IOException if any
     */
    public File createFile( File dir, String filename, String contents )
        throws IOException
    {
        File file = new File( dir, filename );

        file.getParentFile().mkdirs();

        FileWriter writer = null;

        try
        {
            writer = new FileWriter( file );

            IOUtil.copy( new StringReader( contents ), writer );
        }
        finally
        {
            IOUtil.close( writer );
        }

        markForDeletion( file );

        return file;
    }

    /**
     * @param file
     * @return
     * @throws IOException if any
     */
    public String getFileContents( File file )
        throws IOException
    {
        String result = null;

        FileReader reader = null;
        try
        {
            reader = new FileReader( file );

            StringWriter writer = new StringWriter();

            IOUtil.copy( reader, writer );

            result = writer.toString();
        }
        finally
        {
            IOUtil.close( reader );
        }

        return result;
    }

    /** {@inheritDoc} */
    protected void finalize()
        throws Throwable
    {
        maybeWarnAboutCleanUp();

        super.finalize();
    }

    /**
     * @param filename
     * @param content
     * @return
     * @throws IOException if any
     */
    public File createFile( String filename, String content )
        throws IOException
    {
        File dir = createTempDir();
        return createFile( dir, filename, content );
    }
}
