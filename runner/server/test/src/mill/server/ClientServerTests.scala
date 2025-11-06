package mill.server

import mill.api.SystemStreams
import mill.client.lock.Locks
import mill.client.{LaunchedServer, MillServerLauncher, ServerLauncher}
import mill.constants.{DaemonFiles, Util}
import utest.*

import java.io.*
import java.util.Optional
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import concurrent.duration.*

/**
 * Exercises the client-server logic in memory, using in-memory locks
 * and in-memory clients and servers
 */
object ClientServerTests extends TestSuite {

  val ENDL = System.lineSeparator()
  class EchoServer(
      override val processId: Long,
      daemonDir: os.Path,
      locks: Locks,
      testLogEvenWhenServerIdWrong: Boolean,
      commandSleepMillis: Int = 0
  ) extends MillDaemonServer[Option[Int]](
        daemonDir,
        1000.millis,
        locks,
        testLogEvenWhenServerIdWrong
      )
      with Runnable {

    override def outLock = mill.client.lock.Lock.memory()

    override def outFolder = os.temp.dir()

    def initialStateCache = None

    override def serverLog0(s: String) = {
      println(s)
      super.serverLog0(s)
    }

    @volatile var runCompleted = false
    override def run() = {
      super.run()
      runCompleted = true
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
        systemExit: Server.StopServer
    ) = {
      Thread.sleep(commandSleepMillis)
      if (!runCompleted) {
        val reader = new BufferedReader(new InputStreamReader(streams.in))
        val str = reader.readLine()
        Thread.sleep(200)
        if (args.nonEmpty) {
          streams.out.println(str + args(0))
        }
        env.toSeq.sortBy(_._1).foreach {
          case (key, value) => streams.out.println(s"$key=$value")
        }
        if (args.nonEmpty) {
          streams.err.println(str.toUpperCase + args(0))
        }
        streams.out.flush()
        streams.err.flush()
      }
      (true, None)
    }
  }

  class Tester(testLogEvenWhenServerIdWrong: Boolean, commandSleepMillis: Int = 0) {

    var nextServerId: Int = 0
    val terminatedServers = collection.mutable.Set.empty[String]
    val dest = os.pwd / "out"
    os.makeDir.all(dest)
    val outDir = os.temp.dir(dest, deleteOnExit = false)

    val memoryLock = Locks.memory()

    def apply(
        env: Map[String, String] = Map(),
        args: Array[String] = Array(),
        forceFailureForTestingMillisDelay: Int = -1
    ) = {
      val in = new ByteArrayInputStream(s"hello$ENDL".getBytes())
      val out = new ByteArrayOutputStream()
      val err = new ByteArrayOutputStream()
      val daemonDir = outDir / "server-0"
      val result = new MillServerLauncher(
        ServerLauncher.Streams(in, out, err),
        env.asJava,
        args,
        Optional.of(memoryLock),
        forceFailureForTestingMillisDelay
      ) {
        def initServer(daemonDir: Path, locks: Locks) = {
          nextServerId += 1
          // Use a negative process ID to indicate we're not a real process.
          val processId = -nextServerId
          val t = Thread(EchoServer(
            processId,
            os.Path(daemonDir, os.pwd),
            locks,
            testLogEvenWhenServerIdWrong,
            commandSleepMillis = commandSleepMillis
          ))
          t.start()
          LaunchedServer.NewThread(t, () => { /* do nothing */ })
        }
      }.run(
        daemonDir.relativeTo(os.pwd).toNIO,
        "",
        msg => println(s"MillServerLauncher: $msg")
      )

      ClientResult(
        result,
        daemonDir,
        outDir,
        out.toString,
        err.toString
      )
    }

  }

  case class ClientResult(
      exitCode: Int,
      daemonDir: os.Path,
      outDir: os.Path,
      out: String,
      err: String
  ) {
    def logsForSuffix(suffix: String) = {
      os.read
        .lines(daemonDir / DaemonFiles.serverLog)
        .iterator
        .collect {
          case s if s.endsWith(" " + suffix) =>
            // Example line:
            // pid:-1 2025-08-09T17:37:07.870828643 shutting down due inactivity
            s.split(' ').head
        }
        .toSeq
    }

    def logsForServerId(serverId: String) = {
      os.read
        .lines(daemonDir / DaemonFiles.serverLog)
        .iterator
        .collect {
          case s if s.startsWith(serverId + " ") =>
            // Example line:
            // pid:-1 2025-08-09T17:37:07.870828643 shutting down due inactivity
            s.split(" ", 3).last
        }
        .toSeq
    }
  }

  def tests = Tests {

    test("hello") - {
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

        assert(res2.logsForSuffix("shutting down due inactivity") == Seq("pid:-1"))
        assert(res2.logsForSuffix("exiting server") == Seq("pid:-1"))

        // Have a third client spawn/connect-to a new server at the same path
        val res3 = tester(args = Array(" World"))
        assert(
          res3.out == s"hello World$ENDL",
          res3.err == s"HELLO World$ENDL"
        )

        // Make sure if we delete the out dir, the server notices and exits
        Thread.sleep(500)
        os.remove.all(res3.outDir)
        Thread.sleep(1000)

        assert(res3.logsForServerId("pid:-2").exists(_.contains("processId file missing")))
        assert(res3.logsForSuffix("exiting server") == Seq("pid:-2"))
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
      import concurrent.*
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
      // but the daemonDir is placed in different subfolders
      assert(resF1.daemonDir == resF2.daemonDir)
      assert(resF2.daemonDir == resF3.daemonDir)

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
      val res1 = assertThrows[Exception] {
        tester.apply(args = Array(" World"), forceFailureForTestingMillisDelay = 100)
      }

      val s"Force failure for testing: $pathStr" = res1.getMessage: @unchecked
      Thread.sleep(100) // give a moment for logs to all turn up on disk
      val logLines = os.read.lines(os.Path(pathStr, os.pwd) / "server.log")

      assert(
        logLines.exists(line =>
          line.startsWith("pid:-1 ") && line.contains(
            "client interrupted while server was executing command"
          )
        ),
        logLines.exists(line =>
          line.startsWith("pid:-1 ") && line.contains("exiting server")
        )
      )
    }
    test("longCommandNotInterrupted") {
      // Make sure that when the command at 3000ms takes longer than the server
      // timeout at 1000ms, the command still finishes running and the server doesn't
      // shut down half way through
      val tester = new Tester(testLogEvenWhenServerIdWrong = true, commandSleepMillis = 3000)
      val res1 = tester(args = Array("world"))
      assert(
        res1.out == s"helloworld$ENDL",
        res1.err == s"HELLOworld$ENDL"
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
