package mill.daemon

import mill.api.SystemStreams
import mill.api.daemon.Watchable
import mill.internal.Colors
import utest.*

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.mutable

object WatchingTests extends TestSuite {
  private case class CloseTrackedResult(
      name: String,
      closed: mutable.Buffer[String]
  ) extends Watching.Result
      with AutoCloseable {
    override def watched: Seq[Watchable] = Nil
    override def errorOpt: Option[String] = None
    override def close(): Unit = closed += name
  }

  def tests: Tests = Tests {
    test("watchLoop closes current state when interrupted") {
      val closed = mutable.Buffer.empty[String]
      val evaluated = new CountDownLatch(1)
      val daemonDir = os.temp.dir()

      val thread = new Thread(
        () =>
          try {
            Watching.watchLoop(
              ringBell = false,
              watch = Some(Watching.WatchArgs(
                setIdle = _ => (),
                colors = Colors.BlackWhite,
                useNotify = false,
                daemonDir = daemonDir
              )),
              streams = new SystemStreams(
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()),
                new ByteArrayInputStream(Array.emptyByteArray)
              ),
              evaluate = new Watching.Evaluate[CloseTrackedResult] {
                override def apply(
                    skipSelectiveExecution: Boolean,
                    previousState: Option[CloseTrackedResult]
                ): CloseTrackedResult = {
                  val _ = skipSelectiveExecution
                  val _ = previousState
                  evaluated.countDown()
                  CloseTrackedResult("current", closed)
                }
              }
            )
            ()
          } catch {
            case _: InterruptedException => ()
          }
      )

      thread.start()
      assert(evaluated.await(5, TimeUnit.SECONDS))

      thread.interrupt()
      thread.join(5000L)

      assert(!thread.isAlive())
      assert(closed.toSeq == Seq("current"))
    }
  }
}
