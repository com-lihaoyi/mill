import utest._
import java.io.{ByteArrayOutputStream, File, PrintStream}
import java.nio.file.Files
import mill.main.client.FileToStreamTailer

object FileToStreamTailerTest extends TestSuite {

  val tests = Tests {

    test("handleNonExistingFile") {
      val bas = new ByteArrayOutputStream()
      val ps = new PrintStream(bas)

      val file = File.createTempFile("tailer", "")
      assert(file.delete())

      val tailer = new FileToStreamTailer(file, ps, 10)
      try {
        tailer.start()
        Thread.sleep(200)
        assert(bas.toString == "")
      } finally {
        tailer.close()
      }
    }

    test("handleNoExistingFileThatAppearsLater") {
      val bas = new ByteArrayOutputStream()
      val ps = new PrintStream(bas)

      val file = File.createTempFile("tailer", "")
      assert(file.delete())

      val tailer = new FileToStreamTailer(file, ps, 10)
      try {
        tailer.start()
        Thread.sleep(100)
        assert(bas.toString == "")

        val out = new PrintStream(Files.newOutputStream(file.toPath))
        try {
          out.println("log line")
          assert(file.exists())
          Thread.sleep(100)
          assert(bas.toString == s"log line${System.lineSeparator()}")
        } finally {
          out.close()
        }
      } finally {
        tailer.close()
      }
    }

    test("handleExistingInitiallyEmptyFile") {
      val bas = new ByteArrayOutputStream()
      val ps = new PrintStream(bas)

      val file = File.createTempFile("tailer", "")
      assert(file.exists())

      val tailer = new FileToStreamTailer(file, ps, 10)
      try {
        tailer.start()
        Thread.sleep(100)
        assert(bas.toString == "")

        val out = new PrintStream(Files.newOutputStream(file.toPath))
        try {
          out.println("log line")
          assert(file.exists())
          Thread.sleep(100)
          assert(bas.toString == s"log line${System.lineSeparator()}")
        } finally {
          out.close()
        }
      } finally {
        tailer.close()
      }
    }

    test("handleExistingFileWithOldContent") {
      val bas = new ByteArrayOutputStream()
      val ps = new PrintStream(bas)

      val file = File.createTempFile("tailer", "")
      assert(file.exists())

      val out = new PrintStream(Files.newOutputStream(file.toPath))
      try {
        out.println("old line 1")
        out.println("old line 2")
        val tailer = new FileToStreamTailer(file, ps, 10)
        try {
          tailer.start()
          Thread.sleep(500)
          assert(bas.toString == "")
          out.println("log line")
          assert(file.exists())
          Thread.sleep(500)
          assert(bas.toString.trim == "log line")
        } finally {
          tailer.close()
        }
      } finally {
        out.close()
      }
    }

    // Ignored Test: Uncomment to test
    /*
    test("handleExistingEmptyFileWhichDisappearsAndComesBack") {
      val bas = new ByteArrayOutputStream()
      val ps = new PrintStream(bas)

      val file = File.createTempFile("tailer", "")
      assert(file.exists())

      val tailer = new FileToStreamTailer(file, ps, 10)
      try {
        tailer.start()
        Thread.sleep(100)
        assert(bas.toString == "")

        val out = new PrintStream(Files.newOutputStream(file.toPath))
        try {
          out.println("log line 1")
          out.println("log line 2")
          assert(file.exists())
          Thread.sleep(100)
          assert(
            bas.toString == s"log line 1${System.lineSeparator()}log line 2${System.lineSeparator()}"
          )
        } finally {
          out.close()
        }

        assert(file.delete())
        Thread.sleep(100)

        val outNew = new PrintStream(Files.newOutputStream(file.toPath))
        try {
          outNew.println("new line")
          assert(file.exists())
          Thread.sleep(100)
          assert(
            bas.toString == s"log line 1${System.lineSeparator()}log line 2${System.lineSeparator()}new line${System.lineSeparator()}"
          )
        } finally {
          outNew.close()
        }
      } finally {
        tailer.close()
      }
    }
     */
  }
}
