package mill.main.server

import java.io._
import mill.main.client.Util
import mill.main.client.ServerFiles
import mill.main.client.lock.Locks
import mill.api.SystemStreams

import scala.jdk.CollectionConverters._
import utest._

import java.nio.file.Path

/**
 * Exercises the client-server logic in memory, using in-memory locks
 * and in-memory clients and servers
 */
object ClientServerTests extends TestSuite {

  val ENDL = System.lineSeparator()
  class EchoServer(
      override val serverId: String,
      serverDir: os.Path,
      locks: Locks,
      testLogEvenWhenServerIdWrong: Boolean
  ) extends Server[Option[Int]](serverDir, 1000, locks, testLogEvenWhenServerIdWrong)
      with Runnable {
    override def exitServer() = {
      serverLog("exiting server")
      super.exitServer()
    }
    def stateCache0 = None

    override def serverLog0(s: String) = {
      println(s)
      super.serverLog0(s)
    }

    def main0(
        args: Array[String],
        stateCache: Option[Int],
        mainInteractive: Boolean,
        streams: SystemStreams,
        env: Map[String, String],
        setIdle: Boolean => Unit,
        systemProperties: Map[String, String],
        initialSystemProperties: Map[String, String],
        systemExit: Int => Nothing
    ) = {

      val reader = new BufferedReader(new InputStreamReader(streams.in))
      val str = reader.readLine()
      Thread.sleep(200)
      if (args.nonEmpty) {
        streams.out.println(str + args(0))
      }
      env.toSeq.sortBy(_._1).foreach {
        case (key, value) => streams.out.println(s"$key=$value")
      }
      systemProperties.toSeq.sortBy(_._1).foreach {
        case (key, value) => streams.out.println(s"$key=$value")
      }
      if (args.nonEmpty) {
        streams.err.println(str.toUpperCase + args(0))
      }
      streams.out.flush()
      streams.err.flush()
      (true, None)
    }
  }

  class Tester(testLogEvenWhenServerIdWrong: Boolean) {

    var nextServerId: Int = 0
    val terminatedServers = collection.mutable.Set.empty[String]
    val dest = os.pwd / "out"
    os.makeDir.all(dest)
    val outDir = os.temp.dir(dest, deleteOnExit = false)

    val memoryLocks = Array.fill(10)(Locks.memory());

    def apply(
        env: Map[String, String] = Map(),
        args: Array[String] = Array(),
        forceFailureForTestingMillisDelay: Int = -1
    ) = {
      val in = new ByteArrayInputStream(s"hello$ENDL".getBytes())
      val out = new ByteArrayOutputStream()
      val err = new ByteArrayOutputStream()
      val result = new mill.main.client.ServerLauncher(
        in,
        new PrintStream(out),
        new PrintStream(err),
        env.asJava,
        args,
        memoryLocks,
        forceFailureForTestingMillisDelay
      ) {
        def preRun(serverDir: Path) = { /*do nothing*/ }
        def initServer(serverDir: Path, b: Boolean, locks: Locks) = {
          val serverId = "server-" + nextServerId
          nextServerId += 1
          new Thread(new EchoServer(
            serverId,
            os.Path(serverDir, os.pwd),
            locks,
            testLogEvenWhenServerIdWrong
          )).start()
        }
      }.acquireLocksAndRun(outDir.relativeTo(os.pwd).toString)

      ClientResult(
        result.exitCode,
        os.Path(result.serverDir, os.pwd),
        outDir,
        out.toString,
        err.toString
      )
    }

  }

  case class ClientResult(
      exitCode: Int,
      serverDir: os.Path,
      outDir: os.Path,
      out: String,
      err: String
  ) {
    def logsFor(suffix: String) = {
      os.read
        .lines(serverDir / ServerFiles.serverLog)
        .collect { case s if s.endsWith(" " + suffix) => s.dropRight(1 + suffix.length) }
    }
  }

  def tests = Tests {

    test("hello") - retry(3) {
      // Continue logging when out folder is deleted so we can see the logs
      // and ensure the correct code path is taken as the server exits
      val tester = new Tester(testLogEvenWhenServerIdWrong = true)
      val res1 = tester(args = Array("world"))

      assert(
        res1.out == s"helloworld$ENDL",
        res1.err == s"HELLOworld$ENDL"
      )

      // A second client in sequence connect to the same server
      val res2 = tester(args = Array(" WORLD"))

      assert(
        res2.out == s"hello WORLD$ENDL",
        res2.err == s"HELLO WORLD$ENDL"
      )

      if (!Util.isWindows) {
        // Make sure the server times out of not used for a while
        Thread.sleep(2000)

        assert(res2.logsFor("Interrupting after 1000ms") == Seq("server-0"))
        assert(res2.logsFor("exiting server") == Seq("server-0"))

        // Have a third client spawn/connect-to a new server at the same path
        val res3 = tester(args = Array(" World"))
        assert(
          res3.out == s"hello World$ENDL",
          res3.err == s"HELLO World$ENDL"
        )

        // Make sure if we delete the out dir, the server notices and exits
        Thread.sleep(500)
        os.remove.all(res3.outDir)
        Thread.sleep(500)

        assert(res3.logsFor("serverId file missing") == Seq("server-1"))
        assert(res3.logsFor("exiting server") == Seq("server-1"))
      }
    }
    test("dontLogWhenOutFolderDeleted") - retry(3) {
      val tester = new Tester(testLogEvenWhenServerIdWrong = false)
      val res1 = tester(args = Array("world"))

      assert(
        res1.out == s"helloworld$ENDL",
        res1.err == s"HELLOworld$ENDL"
      )

      if (!Util.isWindows) {
        // Make sure if we delete the `out/` folder, the server notices
        // and exits and does not re-create the deleted `out/` folder
        Thread.sleep(500)
        os.remove.all(res1.outDir)
        Thread.sleep(2000)

        assert(!os.exists(res1.outDir))
      }
    }

    test("concurrency") {
      val tester = new Tester(testLogEvenWhenServerIdWrong = false)
      // Make sure concurrently running client commands results in multiple processes
      // being spawned, running in different folders
      import concurrent._
      import concurrent.ExecutionContext.Implicits.global
      val f1 = Future(tester(args = Array(" World")))
      val f2 = Future(tester(args = Array(" WORLD")))
      val f3 = Future(tester(args = Array(" wOrLd")))
      val resF1 = Await.result(f1, duration.Duration.Inf)
      val resF2 = Await.result(f2, duration.Duration.Inf)
      val resF3 = Await.result(f3, duration.Duration.Inf)

      // Mutiple server processes live in same out folder
      assert(resF1.outDir == resF2.outDir)
      assert(resF2.outDir == resF3.outDir)
      // but the serverDir is placed in different subfolders
      assert(resF1.serverDir != resF2.serverDir)
      assert(resF2.serverDir != resF3.serverDir)

      assert(resF1.out == s"hello World$ENDL")
      assert(resF2.out == s"hello WORLD$ENDL")
      assert(resF3.out == s"hello wOrLd$ENDL")
    }

    test("clientLockReleasedOnFailure") {
      val tester = new Tester(testLogEvenWhenServerIdWrong = false)
      // When the client gets interrupted via Ctrl-C, we exit the server immediately. This
      // is because Mill ends up executing arbitrary JVM code, and there is no generic way
      // to interrupt such an execution. The two options are to leave the server running
      // for an unbounded duration, or kill the server process and take a performance hit
      // on the next cold startup. Mill chooses the second option.
      import concurrent._
      import concurrent.ExecutionContext.Implicits.global
      val res1 = intercept[Exception] {
        tester.apply(args = Array(" World"), forceFailureForTestingMillisDelay = 100)
      }

      val s"Force failure for testing: $pathStr" = res1.getMessage
      Thread.sleep(100) // give a moment for logs to all turn up on disk
      val logLines = os.read.lines(os.Path(pathStr, os.pwd) / "server.log")

      assert(
        logLines.takeRight(2) ==
          Seq(
            "server-0 client interrupted while server was executing command",
            "server-0 exiting server"
          )
      )
    }

    test("envVars") - retry(3) {
      val tester = new Tester(testLogEvenWhenServerIdWrong = false)
      // Make sure the simple "have the client start a server and
      // exchange one message" workflow works from end to end.

      def longString(s: String) = Array.fill(1000)(s).mkString
      val b1000 = longString("b")
      val c1000 = longString("c")
      val a1000 = longString("a")

      val env = Map(
        "a" -> a1000,
        "b" -> b1000,
        "c" -> c1000
      )

      val res1 = tester(env = env)
      val expected = s"a=$a1000${ENDL}b=$b1000${ENDL}c=$c1000$ENDL"

      assert(
        res1.out == expected,
        res1.err == ""
      )

      val path = List(
        "/Users/foo/Library/Haskell/bin",
        "/usr/local/git/bin",
        "/sw/bin/",
        "/usr/local/bin",
        "/usr/local/",
        "/usr/local/sbin",
        "/usr/local/mysql/bin",
        "/usr/local/bin",
        "/usr/bin",
        "/bin",
        "/usr/sbin",
        "/sbin",
        "/opt/X11/bin",
        "/usr/local/MacGPG2/bin",
        "/Library/TeX/texbin",
        "/usr/local/bin/",
        "/Users/foo/bin",
        "/Users/foo/go/bin",
        "~/.bloop"
      )

      val pathEnvVar = path.mkString(":")
      val res2 = tester(env = Map("PATH" -> pathEnvVar))

      val expected2 = s"PATH=$pathEnvVar$ENDL"

      assert(
        res2.out == expected2,
        res2.err == ""
      )
    }
  }
}
