package mill.client

import utest._

import java.io.{ByteArrayOutputStream, PrintStream}

object FileToStreamTailerTests extends TestSuite {
  val tests = Tests {
    test("handleNonExistingFile") {
      val bas = new ByteArrayOutputStream()
      val ps = new PrintStream(bas)
      val file = os.temp(deleteOnExit = false)
      os.remove(file)

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
      val file = os.temp(deleteOnExit = false)
      os.remove(file)

      val tailer = new FileToStreamTailer(file, ps, 10)
      try {
        tailer.start()
        Thread.sleep(100)
        assert(bas.toString == "")

        val out = new PrintStream(os.write.outputStream(file))
        try {
          out.println("log line")
          assert(os.exists(file))
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
      val file = os.temp(deleteOnExit = false)
      assert(os.exists(file))

      val tailer = new FileToStreamTailer(file, ps, 10)
      try {
        tailer.start()
        Thread.sleep(100)
        assert(bas.toString == "")

        val out = new PrintStream(os.write.outputStream(file))
        try {
          out.println("log line")
          assert(os.exists(file))
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
      val file = os.temp(deleteOnExit = false)
      assert(os.exists(file))

      val out = new PrintStream(os.write.outputStream(file))
      try {
        out.println("old line 1")
        out.println("old line 2")
        val tailer = new FileToStreamTailer(file, ps, 10)
        try {
          tailer.start()
          Thread.sleep(500)
          assert(bas.toString == "")
          out.println("log line")
          assert(os.exists(file))
          Thread.sleep(500)
          assert(bas.toString.trim == "log line")
        } finally {
          tailer.close()
        }
      } finally {
        out.close()
      }
    }
  }
}
