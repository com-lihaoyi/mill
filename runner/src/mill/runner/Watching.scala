package mill.runner

import mill.api.internal
import mill.util.{Colors, Watchable}
import mill.api.SystemStreams

import java.io.InputStream
import scala.annotation.tailrec

/**
 * Logic around the "watch and wait" functionality in Mill: re-run on change,
 * re-run when the user presses Enter, printing status messages, etc.
 */
@internal
object Watching {
  case class Result[T](watched: Seq[Watchable], error: Option[String], result: T)

  def watchLoop[T](
      ringBell: Boolean,
      watch: Boolean,
      streams: SystemStreams,
      setIdle: Boolean => Unit,
      evaluate: Option[T] => Result[T],
      colors: Colors
  ): (Boolean, T) = {
    var prevState: Option[T] = None
    while (true) {
      val Result(watchables, errorOpt, result) = evaluate(prevState)
      prevState = Some(result)
      errorOpt.foreach(streams.err.println)
      if (ringBell) {
        if (errorOpt.isEmpty) println("\u0007")
        else {
          println("\u0007")
          Thread.sleep(250)
          println("\u0007")
        }
      }

      if (!watch) {
        return (errorOpt.isEmpty, result)
      }

      val alreadyStale = watchables.exists(!_.validate())
      if (!alreadyStale) {
        Watching.watchAndWait(streams, setIdle, streams.in, watchables, colors)
      }
    }
    ???
  }

  def watchAndWait(
      streams: SystemStreams,
      setIdle: Boolean => Unit,
      stdin: InputStream,
      watched: Seq[Watchable],
      colors: Colors
  ): Unit = {
    setIdle(true)
    val watchedPaths = watched.collect { case p: Watchable.Path => p.p.path }
    val watchedValues = watched.size - watchedPaths.size

    val watchedValueStr = if (watchedValues == 0) "" else s" and $watchedValues other values"

    streams.err.println(
      colors.info(
        s"Watching for changes to ${watchedPaths.size} paths$watchedValueStr... (Enter to re-run, Ctrl-C to exit)"
      ).toString
    )

    statWatchWait(watched, stdin)
    setIdle(false)
  }

  def statWatchWait(watched: Seq[Watchable], stdin: InputStream): Unit = {
    val buffer = new Array[Byte](4 * 1024)

    @tailrec def statWatchWait0(): Unit = {
      if (watched.forall(_.validate())) {
        if (lookForEnterKey()) ()
        else {
          Thread.sleep(100)
          statWatchWait0()
        }
      }
    }

    @tailrec def lookForEnterKey(): Boolean = {
      if (stdin.available() == 0) false
      else stdin.read(buffer) match {
        case 0 | -1 => false
        case n =>
          buffer.indexOf('\n') match {
            case -1 => lookForEnterKey()
            case i =>
              if (i >= n) lookForEnterKey()
              else true
          }
      }
    }

    statWatchWait0()
  }

}
