package mill.main.client;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import utest._

object FileToStreamTailerTest extends TestSuite {

  // @org.junit.Rule
  // public RetryRule retryRule = new RetryRule(3);

  val tests = Tests {

    test("handleNonExistingFile") {
      val bas = new ByteArrayOutputStream();
      val ps = new PrintStream(bas);

      val file = File.createTempFile("tailer", "");
      assert(file.delete());

      try {
        val tailer = new FileToStreamTailer(file, ps, 10)
        tailer.start()

        // Sleep to simulate a short delay
        Thread.sleep(200)

        // Assert that the output stream is still empty
        assert(bas.toString == "")

      } finally {
        ps.close()
        bas.close()
      }
    }

    test("handleNonExistingFileThatAppearsLater") {
      val bas = new ByteArrayOutputStream()
      val ps = new PrintStream(bas)

      // Create and immediately delete a temporary file
      val file = File.createTempFile("tailer", "")
      assert(file.delete())

      // Simulate FileToStreamTailer behavior
      try {
        val tailer = new FileToStreamTailer(file, ps, 10)
        tailer.start()

        // Sleep to simulate waiting for the file to appear
        Thread.sleep(100)
        assert(bas.toString == "")

        // Write to the file and verify that the tailer processes it
        val out = new PrintStream(Files.newOutputStream(file.toPath))
        try {
          out.println("log line")
          assert(file.exists())
          Thread.sleep(200)
          assert(bas.toString == "log line" + System.lineSeparator())
        } finally {
          out.close()
        }
      } finally {
        ps.close()
        bas.close()
      }
    }

    test("handleExistingInitiallyEmptyFile") {
      val bas = new ByteArrayOutputStream()
      val ps = new PrintStream(bas)

      // Create an empty temporary file
      val file = File.createTempFile("tailer", "")
      assert(file.exists())

      try {
        val tailer = new FileToStreamTailer(file, ps, 10)
        tailer.start()
        Thread.sleep(100)

        // File is empty initially, so no output is expected
        assert(bas.toString == "")

        // Write to the file and verify the tailer processes the new line
        val out = new PrintStream(Files.newOutputStream(file.toPath))
        try {
          out.println("log line")
          assert(file.exists())
          Thread.sleep(100)
          println("bas.toString")
          println(bas.toString)
          println("====")
          assert(bas.toString == "log line" + System.lineSeparator())
        } finally {
          out.close()
        }
      } finally {
        ps.close()
        bas.close()
      }
    }

    test("handleExistingFileWithOldContent") {
      val bas = new ByteArrayOutputStream()
      val ps = new PrintStream(bas)

      // Create a temporary file with old content
      val file = File.createTempFile("tailer", "")
      assert(file.exists())

      val out = new PrintStream(Files.newOutputStream(file.toPath))
      try {
        // Write old content to the file
        out.println("old line 1")
        out.println("old line 2")

        // Start the tailer after writing old content
        val tailer = new FileToStreamTailer(file, ps, 10)
        try {
          tailer.start()
          Thread.sleep(500)

          // The tailer should ignore old content
          assert(bas.toString == "")

          // Write a new line and verify the tailer processes it
          out.println("log line")
          assert(file.exists())
          Thread.sleep(500)
          assert(bas.toString.trim == "log line")
        } finally {
          tailer.stop()
        }
      } finally {
        out.close()
        ps.close()
        bas.close()
      }
    }

  }

}
