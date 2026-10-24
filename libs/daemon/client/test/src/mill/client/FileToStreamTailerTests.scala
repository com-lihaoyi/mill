package mill.client

import utest._

import java.io.{ByteArrayOutputStream, PrintStream}

object FileToStreamTailerTests extends TestSuite {
  val tests = Tests {
    test("handleNonExistingFile") {
      val bas = ByteArrayOutputStream()
      val ps = PrintStream(bas)
      val file = os.temp(deleteOnExit = false)
      os.remove(file)

      val tailer = FileToStreamTailer(file, ps, 10)
      try {
        tailer.start()
        Thread.sleep(200)
        assert(bas.toString == "")
      } finally {
        tailer.close()
      }
    }

    test("handleNoExistingFileThatAppearsLater") {
      val bas = ByteArrayOutputStream()
      val ps = PrintStream(bas)
      val file = os.temp(deleteOnExit = false)
      os.remove(file)

      val tailer = FileToStreamTailer(file, ps, 10)
      try {
        tailer.start()
        Thread.sleep(100)
        assert(bas.toString == "")

        val out = PrintStream(os.write.outputStream(file))
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
      val bas = ByteArrayOutputStream()
      val ps = PrintStream(bas)
      val file = os.temp(deleteOnExit = false)
      assert(os.exists(file))

      val tailer = FileToStreamTailer(file, ps, 10)
      try {
        tailer.start()
        Thread.sleep(100)
        assert(bas.toString == "")

        val out = PrintStream(os.write.outputStream(file))
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
      val bas = ByteArrayOutputStream()
      val ps = PrintStream(bas)
      val file = os.temp(deleteOnExit = false)
      assert(os.exists(file))

      val out = PrintStream(os.write.outputStream(file))
      try {
        out.println("old line 1")
        out.println("old line 2")
        val tailer = FileToStreamTailer(file, ps, 10)
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

    test("printsLineOnlyOnceItIsTerminated") {
      val bas = ByteArrayOutputStream()
      val ps = PrintStream(bas)
      val file = os.temp(deleteOnExit = false)

      val tailer = FileToStreamTailer(file, ps, 10)
      try {
        tailer.start()
        Thread.sleep(100)
        val out = PrintStream(os.write.outputStream(file))
        try {
          out.print("log ")
          out.flush()
          Thread.sleep(100)
          assert(bas.toString == "")
          out.println("line")
          Thread.sleep(100)
          assert(bas.toString == s"log line${System.lineSeparator()}")
        } finally {
          out.close()
        }
      } finally {
        tailer.close()
      }
    }

    test("printsPartialLineWhenFlushedAndJoinsLaterContentCorrectly") {
      val bas = ByteArrayOutputStream()
      val ps = PrintStream(bas)
      val file = os.temp(deleteOnExit = false)

      val tailer = FileToStreamTailer(file, ps, 10)
      try {
        tailer.start()
        Thread.sleep(100)
        val out = PrintStream(os.write.outputStream(file))
        try {
          out.print("log ")
          out.flush()
          tailer.flush()
          Thread.sleep(100)
          assert(bas.toString == "log ")
          out.println("line")
          Thread.sleep(100)
          assert(bas.toString == s"log line${System.lineSeparator()}")
        } finally {
          out.close()
        }
      } finally {
        tailer.close()
      }
    }

    test("flushPrintsUnterminatedLine") {
      val bas = ByteArrayOutputStream()
      val ps = PrintStream(bas)
      val file = os.temp(deleteOnExit = false)

      val tailer = FileToStreamTailer(file, ps, 10)
      try {
        tailer.start()
        Thread.sleep(100)
        val out = PrintStream(os.write.outputStream(file))
        try {
          out.print("partial line")
          out.flush()
          Thread.sleep(100)
          assert(bas.toString == "")
          tailer.flush()
          assert(bas.toString == s"partial line")
        } finally {
          out.close()
        }
      } finally {
        tailer.close()
      }
    }

    test("closePrintsEverythingWrittenBeforeItIsCalled") {
      val bas = ByteArrayOutputStream()
      val ps = PrintStream(bas)
      val file = os.temp(deleteOnExit = false)

      val tailer = FileToStreamTailer(file, ps, 10)
      tailer.start()
      Thread.sleep(100)
      val out = PrintStream(os.write.outputStream(file))
      try {
        out.print("last line")
        out.flush()
      } finally {
        out.close()
      }
      tailer.close()
      assert(bas.toString == s"last line")
    }

    test("printPreservesLF") {
      val bas = ByteArrayOutputStream()
      val ps = PrintStream(bas)
      val file = os.temp(deleteOnExit = false)

      val tailer = FileToStreamTailer(file, ps, 10)
      tailer.start()
      Thread.sleep(100)
      val out = PrintStream(os.write.outputStream(file))
      try {
        out.print("line 1\n")
        out.print("line 2\n")
        out.print("line 3\n")
        out.print("line 4\n")
      } finally {
        out.close()
      }
      tailer.close()
      assert(bas.toString == "line 1\nline 2\nline 3\nline 4\n")
    }

    test("printPreservesCRLF") {
      val bas = ByteArrayOutputStream()
      val ps = PrintStream(bas)
      val file = os.temp(deleteOnExit = false)

      val tailer = FileToStreamTailer(file, ps, 10)
      tailer.start()
      Thread.sleep(100)
      val out = PrintStream(os.write.outputStream(file))
      try {
        out.print("line 1\r\n")
        out.print("line 2\r\n")
        out.print("line 3\r\n")
        out.print("line 4\r\n")
      } finally {
        out.close()
      }
      tailer.close()
      assert(bas.toString == "line 1\r\nline 2\r\nline 3\r\nline 4\r\n")
    }
  }
}
