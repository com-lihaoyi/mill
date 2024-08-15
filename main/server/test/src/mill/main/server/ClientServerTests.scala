package mill.main.server

import java.io._
import mill.main.client.Util
import mill.main.client.lock.Locks
import mill.api.SystemStreams

import scala.jdk.CollectionConverters._
import utest._


/**
 * Exercises the client-server logic in memory, using in-memory locks
 * and in-memory clients and servers
 */
object ClientServerTests extends TestSuite {

  val ENDL = System.lineSeparator()
  class EchoServer(override val serverId: String, log: String => Unit, serverDir: os.Path, locks: Locks)
    extends Server[Option[Int]](serverDir, 1000, locks) with Runnable {
    override def exitServer() = {
      log(serverId + " exiting server")
      super.exitServer()
    }
    def stateCache0 = None

    override def serverLog(s: String) = {
      log(serverId + " " + s)
    }
    def main0(
               args: Array[String],
               stateCache: Option[Int],
               mainInteractive: Boolean,
               streams: SystemStreams,
               env: Map[String, String],
               setIdle: Boolean => Unit,
               systemProperties: Map[String, String],
               initialSystemProperties: Map[String, String]
             ) = {

      val reader = new BufferedReader(new InputStreamReader(streams.in))
      val str = reader.readLine()
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

  class Tester {

    var nextServerId: Int = 0
    val terminatedServers = collection.mutable.Set.empty[String]
    val dest = os.pwd / "out"
    os.makeDir.all(dest)
    val serverDir = os.temp.dir(dest)

    val locks = Locks.memory()

    def spawnEchoServer(serverDir: os.Path, locks: Locks): Unit = {
      val serverId = "server-" + nextServerId
      nextServerId += 1
      new Thread(new EchoServer(serverId, log, serverDir, locks)).start()
    }
    def log(s: String) = {
      logs.append(s)
      println(s)
    }
    val logs = collection.mutable.Buffer.empty[String]


    def runClient(env: Map[String, String], args: Array[String]) = {
      val in = new ByteArrayInputStream(s"hello${ENDL}".getBytes())
      val out = new ByteArrayOutputStream()
      val err = new ByteArrayOutputStream()
      Server.lockBlock(locks.clientLock) {
        mill.main.client.ServerLauncher.run(
          serverDir.toString,
          () => spawnEchoServer(serverDir, locks),
          locks,
          in,
          out,
          err,
          args,
          env.asJava
        )

        ClientResult(serverDir, new String(out.toByteArray), new String(err.toByteArray))
      }
    }

    def apply(env: Map[String, String], args: Array[String]) = runClient(env, args)

    def logsFor(suffix: String) = {
      logs.collect{case s if s.endsWith(" " + suffix) => s.dropRight(1 + suffix.length)}
    }
  }

  case class ClientResult(serverDir: os.Path, out: String, err: String)

  def tests = Tests {
    "hello" - {
      val tester = new Tester


      // Make sure the simple "have the client start a server and
      // exchange one message" workflow works from end to end.

      assert(
        tester.locks.clientLock.probe(),
        tester.locks.processLock.probe()
      )

      val res1 = tester(Map(), Array("world"))

      assert(
        res1.out == s"helloworld${ENDL}",
        res1.err == s"HELLOworld${ENDL}"
      )

      // Give a bit of time for the server to release the lock and
      // re-acquire it to signal to the client that it"s" done

      assert(
        tester.locks.clientLock.probe(),
        !tester.locks.processLock.probe()
      )

      // A seecond client in sequence connect to the same server
      val res2 = tester(Map(), Array(" WORLD"))

      assert(
        res2.out == s"hello WORLD${ENDL}",
        res2.err == s"HELLO WORLD${ENDL}"
      )

      if (!Util.isWindows) {
        // Make sure the server times out of not used for a while
        Thread.sleep(2000)
        assert(
          tester.locks.clientLock.probe(),
          tester.locks.processLock.probe()
        )

        assert(tester.logsFor("Interrupting after 1000ms") == Seq("server-0"))
        assert(tester.logsFor("exiting server") == Seq("server-0"))

        // Have a third client spawn/connect-to a new server at the same path
        val res3 = tester(Map(), Array(" World"))
        assert(
          res3.out == s"hello World${ENDL}",
          res3.err == s"HELLO World${ENDL}"
        )

        // Make sure if we delete the out dir, the server notices and exits
        os.remove.all(res3.serverDir)
        Thread.sleep(500)

        assert(tester.logsFor("serverId file missing") == Seq("server-1"))
        assert(tester.logsFor("exiting server") == Seq("server-0", "server-1"))

      }
    }

    "envVars" - retry(3) {
      val tester = new Tester

      // Make sure the simple "have the client start a server and
      // exchange one message" workflow works from end to end.

      assert(
        tester.locks.clientLock.probe(),
        tester.locks.processLock.probe()
      )

      def longString(s: String) = Array.fill(1000)(s).mkString
      val b1000 = longString("b")
      val c1000 = longString("c")
      val a1000 = longString("a")

      val env = Map(
        "a" -> a1000,
        "b" -> b1000,
        "c" -> c1000
      )

      val res1 = tester(env, Array())
      val expected = s"a=$a1000${ENDL}b=$b1000${ENDL}c=$c1000${ENDL}"

      assert(
        res1.out == expected,
        res1.err == ""
      )

      assert(
        tester.locks.clientLock.probe(),
        !tester.locks.processLock.probe()
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
      val res2 = tester(Map("PATH" -> pathEnvVar), Array())

      val expected2 = s"PATH=$pathEnvVar${ENDL}"

      assert(
        res2.out == expected2,
        res2.err == ""
      )
    }
  }
}
