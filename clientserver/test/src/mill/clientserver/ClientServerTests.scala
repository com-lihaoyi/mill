package mill.clientserver
import java.io._
import java.nio.file.Path

import utest._
class EchoServer extends ServerMain[Int]{
  def main0(args: Array[String],
            stateCache: Option[Int],
            mainInteractive: Boolean,
            stdin: InputStream,
            stdout: PrintStream,
            stderr: PrintStream) = {

    val reader = new BufferedReader(new InputStreamReader(stdin))
    val str = reader.readLine()
    stdout.println(str + args(0))
    stdout.flush()
    stderr.println(str.toUpperCase + args(0))
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
    val locks = new MemoryLocks()

    (tmpDir, locks)
  }

  def tests = Tests{
    'hello - {
      val (tmpDir, locks) = init()

      def spawnEchoServer(): Unit = {
        new Thread(() => new Server(
          tmpDir.toString,
          new EchoServer(),
          () => (),
          1000,
          locks
        ).run()).start()
      }


      def runClient(arg: String) = {
        val (in, out, err) = initStreams()
        locks.clientLock.lockBlock{
          val c = new Client(
            tmpDir.toString,
            () => spawnEchoServer(),
            locks,
            in,
            out,
            err
          )
          c.run(Array(arg))
          (new String(out.toByteArray), new String(err.toByteArray))
        }
      }

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
  }
}
