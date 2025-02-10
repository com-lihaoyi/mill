package mill.client;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import org.junit.Ignore;
import org.junit.Test;

public class FileToStreamTailerTest {

  @org.junit.Rule
  public RetryRule retryRule = new RetryRule(3);

  @Test
  public void handleNonExistingFile() throws Exception {
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    final PrintStream ps = new PrintStream(bas);

    final File file = File.createTempFile("tailer", "");
    assertTrue(file.delete());

    try (final FileToStreamTailer tailer = new FileToStreamTailer(file, ps, 10); ) {
      tailer.start();
      Thread.sleep(200);
      assertEquals(bas.toString(), "");
    }
  }

  @Test
  public void handleNoExistingFileThatAppearsLater() throws Exception {
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    final PrintStream ps = new PrintStream(bas);

    final File file = File.createTempFile("tailer", "");
    assertTrue(file.delete());

    try (final FileToStreamTailer tailer = new FileToStreamTailer(file, ps, 10); ) {
      tailer.start();
      Thread.sleep(100);
      assertEquals(bas.toString(), "");

      try (PrintStream out = new PrintStream(Files.newOutputStream(file.toPath())); ) {
        out.println("log line");
        assertTrue(file.exists());
        Thread.sleep(100);
        assertEquals(bas.toString(), "log line" + System.lineSeparator());
      }
    }
  }

  @Test
  public void handleExistingInitiallyEmptyFile() throws Exception {
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    final PrintStream ps = new PrintStream(bas);

    final File file = File.createTempFile("tailer", "");
    assertTrue(file.exists());

    try (final FileToStreamTailer tailer = new FileToStreamTailer(file, ps, 10); ) {
      tailer.start();
      Thread.sleep(100);

      assertEquals(bas.toString(), "");

      try (PrintStream out = new PrintStream(Files.newOutputStream(file.toPath())); ) {
        out.println("log line");
        assertTrue(file.exists());
        Thread.sleep(100);
        assertEquals(bas.toString(), "log line" + System.lineSeparator());
      }
    }
  }

  @Test
  public void handleExistingFileWithOldContent() throws Exception {
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    final PrintStream ps = new PrintStream(bas);

    final File file = File.createTempFile("tailer", "");
    assertTrue(file.exists());

    try (PrintStream out = new PrintStream(Files.newOutputStream(file.toPath())); ) {
      out.println("old line 1");
      out.println("old line 2");
      try (final FileToStreamTailer tailer = new FileToStreamTailer(file, ps, 10); ) {
        tailer.start();
        Thread.sleep(500);
        assertEquals(bas.toString(), "");
        out.println("log line");
        assertTrue(file.exists());
        Thread.sleep(500);
        assertEquals(bas.toString().trim(), "log line");
      }
    }
  }

  @Ignore
  @Test
  public void handleExistingEmptyFileWhichDisappearsAndComesBack() throws Exception {
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    final PrintStream ps = new PrintStream(bas);

    final File file = File.createTempFile("tailer", "");
    assertTrue(file.exists());

    try (final FileToStreamTailer tailer = new FileToStreamTailer(file, ps, 10); ) {
      tailer.start();
      Thread.sleep(100);

      assertEquals(bas.toString(), "");

      try (PrintStream out = new PrintStream(Files.newOutputStream(file.toPath())); ) {
        out.println("log line 1");
        out.println("log line 2");
        assertTrue(file.exists());
        Thread.sleep(100);
        assertEquals(
            bas.toString(),
            "log line 1" + System.lineSeparator() + "log line 2" + System.lineSeparator());
      }

      // Now delete file and give some time, then append new lines

      assertTrue(file.delete());
      Thread.sleep(100);

      try (PrintStream out = new PrintStream(Files.newOutputStream(file.toPath())); ) {
        out.println("new line");
        assertTrue(file.exists());
        Thread.sleep(100);
        assertEquals(
            bas.toString(),
            "log line 1" + System.lineSeparator() + "log line 2"
                + System.lineSeparator() + "new line"
                + System.lineSeparator());
      }
    }
  }
}
