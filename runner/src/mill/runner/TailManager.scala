package mill.runner

import mill.api.SystemStreams.ThreadLocalStreams
import mill.client.{FileToStreamTailer, ServerFiles}

import java.io.{OutputStream, PrintStream}

class TailManager(serverDir: os.Path) extends AutoCloseable {
  val tailerRefreshIntervalMillis = 2

  // We need to explicitly manage tailerOut/tailerErr ourselves, rather than relying
  // on System.out/System.err redirects, because those redirects are ThreadLocal and
  // do not affect the tailers which run on their own separate threads
  @volatile var tailerOut: OutputStream = System.out
  @volatile var tailerErr: OutputStream = System.err
  val stdoutTailer = new FileToStreamTailer(
    (serverDir / ServerFiles.stdout).toIO,
    new PrintStream(new ThreadLocalStreams.ProxyOutputStream {
      def delegate(): OutputStream = tailerOut
    }),
    tailerRefreshIntervalMillis
  )
  val stderrTailer = new FileToStreamTailer(
    (serverDir / ServerFiles.stderr).toIO,
    new PrintStream(new ThreadLocalStreams.ProxyOutputStream {
      def delegate(): OutputStream = tailerErr
    }),
    tailerRefreshIntervalMillis
  )

  stdoutTailer.start()
  stderrTailer.start()

  def withOutErr[T](newOut: OutputStream, newErr: OutputStream)(t: => T): T = {
    tailerOut = newOut
    tailerErr = newErr
    try t
    finally {
      tailerOut = System.out
      tailerErr = System.err
    }
  }

  override def close(): Unit = {
    stdoutTailer.close()
    stderrTailer.close()
  }
}
