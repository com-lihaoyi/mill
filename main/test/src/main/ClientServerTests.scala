package mill.main
import java.io._

import mill.main.client.{Util, Locks}

import scala.collection.JavaConverters._
import utest._
class EchoServer extends MillServerMain[Int]{
  def main0(args: Array[String],
            stateCache: Option[Int],
            mainInteractive: Boolean,
            stdin: InputStream,
            stdout: PrintStream,
            stderr: PrintStream,
            env: Map[String, String],
            setIdle: Boolean => Unit) = {

    val reader = new BufferedReader(new InputStreamReader(stdin))
    val str = reader.readLine()
    if (args.nonEmpty){
      stdout.println(str + args(0))
    }
    env.toSeq.sortBy(_._1).foreach{
      case (key, value) => stdout.println(s"$key=$value")
    }
    stdout.flush()
    if (args.nonEmpty){
      stderr.println(str.toUpperCase + args(0))
    }
    stderr.flush()
    (true, None)
  }
}

object ClientServerTests extends TestSuite{
  def initStreams() = {
    val in = new ByteArrayInputStream("hello\n".getBytes())
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    (in, out, err)
  }
  def init() = {
    val tmpDir = java.nio.file.Files.createTempDirectory("")
    val locks = Locks.memory()

    (tmpDir, locks)
  }

  def spawnEchoServer(tmpDir : java.nio.file.Path, locks: Locks): Unit = {
    new Thread(() => new Server(
      tmpDir.toString,
      new EchoServer(),
      () => (),
      1000,
      locks
    ).run()).start()
  }

  def runClientAux(tmpDir : java.nio.file.Path, locks: Locks)
                  (env : Map[String, String], args: Array[String]) = {
    val (in, out, err) = initStreams()
    Server.lockBlock(locks.clientLock){
      mill.main.client.MillClientMain.run(
        tmpDir.toString,
        () => spawnEchoServer(tmpDir, locks),
        locks,
        in,
        out,
        err,
        args,
        env.asJava
      )
      Thread.sleep(100)
      (new String(out.toByteArray), new String(err.toByteArray))
    }
  }

  def tests = Tests{
    'hello - {
      if (!Util.isWindows){
        val (tmpDir, locks) = init()
        def runClient(s: String) = runClientAux(tmpDir, locks)(Map.empty, Array(s))

        // Make sure the simple "have the client start a server and
        // exchange one message" workflow works from end to end.

        assert(
          locks.clientLock.probe(),
          locks.serverLock.probe(),
          locks.processLock.probe()
        )

        val (out1, err1) = runClient("world")

        assert(
          out1 == "helloworld\n",
          err1 == "HELLOworld\n"
        )

        // Give a bit of time for the server to release the lock and
        // re-acquire it to signal to the client that it's done
        Thread.sleep(100)

        assert(
          locks.clientLock.probe(),
          !locks.serverLock.probe(),
          !locks.processLock.probe()
        )

        // A seecond client in sequence connect to the same server
        val (out2, err2) = runClient(" WORLD")

        assert(
          out2 == "hello WORLD\n",
          err2 == "HELLO WORLD\n"
        )

        // Make sure the server times out of not used for a while
        Thread.sleep(2000)
        assert(
          locks.clientLock.probe(),
          locks.serverLock.probe(),
          locks.processLock.probe()
        )

        // Have a third client spawn/connect-to a new server at the same path
        val (out3, err3) = runClient(" World")
        assert(
          out3 == "hello World\n",
          err3 == "HELLO World\n"
        )
      }

      'envVars - {
        if (!Util.isWindows){
          val (tmpDir, locks) = init()

          def runClient(env : Map[String, String]) = runClientAux(tmpDir, locks)(env, Array())

          // Make sure the simple "have the client start a server and
          // exchange one message" workflow works from end to end.

          assert(
            locks.clientLock.probe(),
            locks.serverLock.probe(),
            locks.processLock.probe()
          )

          def longString(s : String) = Array.fill(1000)(s).mkString
          val b1000 = longString("b")
          val c1000 = longString("c")
          val a1000 = longString("a")

          val env = Map(
            "a" -> a1000,
            "b" -> b1000,
            "c" -> c1000
          )


          val (out1, err1) = runClient(env)
          val expected = s"a=$a1000\nb=$b1000\nc=$c1000\n"

          assert(
            out1 == expected,
            err1 == ""
          )

          // Give a bit of time for the server to release the lock and
          // re-acquire it to signal to the client that it's done
          Thread.sleep(100)

          assert(
            locks.clientLock.probe(),
            !locks.serverLock.probe(),
            !locks.processLock.probe()
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
          val (out2, err2) = runClient(Map("PATH" -> pathEnvVar))

          val expected2 = s"PATH=$pathEnvVar\n"

          assert(
            out2 == expected2,
            err2 == ""
          )
        }
      }
    }
  }
}
