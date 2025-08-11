package mill.daemon

import mill.client.FileToStreamTailer
import mill.constants.DaemonFiles
import mill.api.SystemStreamsUtils.ThreadLocalStreams

import java.io.{OutputStream, PrintStream}

class TailManager(daemonDir: os.Path) extends AutoCloseable {
  val tailerRefreshIntervalMillis = 2

  // We need to explicitly manage tailerOut/tailerErr ourselves, rather than relying
  // on System.out/System.err redirects, because those redirects are ThreadLocal and
  // do not affect the tailers which run on their own separate threads
  @volatile var tailerOut: OutputStream = System.out
  @volatile var tailerErr: OutputStream = System.err
  val stdoutTailer = new FileToStreamTailer(
    (daemonDir / DaemonFiles.stdout).toIO,
    new PrintStream(new ThreadLocalStreams.ProxyOutputStream {
      def delegate(): OutputStream = tailerOut
    }),
    tailerRefreshIntervalMillis
  )
  val stderrTailer = new FileToStreamTailer(
    (daemonDir / DaemonFiles.stderr).toIO,
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
