package mill.entrypoint

import mill.util.{ColorLogger, SystemStreams}

import java.io.InputStream
import scala.annotation.tailrec

/**
 * Logic around the "watch and wait" functionality in Mill: re-run on change,
 * re-run when the user presses Enter, printing status messages, etc.
 */
object Watching{
  type Result[T] = (Seq[Watchable], Option[String], Option[T], Boolean)


  def watchLoop[T](logger: ColorLogger,
                   ringBell: Boolean,
                   watch: Boolean,
                   streams: SystemStreams,
                   setIdle: Boolean => Unit,
                   evaluate: () => Result[T]): (Boolean, Option[T]) = {
    while (true) {
      val (watchables, errorOpt, resultOpt, isSuccess) = evaluate()

      errorOpt.foreach(logger.error)
      if (ringBell) {
        if (isSuccess) println("\u0007")
        else {
          println("\u0007")
          Thread.sleep(250)
          println("\u0007")
        }
      }

      if (!watch) {
        return (isSuccess, resultOpt)
      }

      val alreadyStale = watchables.exists(!_.validate())
      if (!alreadyStale) {
        Watching.watchAndWait(logger, setIdle, streams.in, watchables)
      }
    }
    ???
  }

  def watchAndWait(logger: ColorLogger, setIdle: Boolean => Unit, stdin: InputStream, watched: Seq[Watchable]) = {
    setIdle(true)
    val watchedPaths = watched.count(_.isInstanceOf[Watchable.Path])
    val watchedValues = watched.size - watchedPaths

    val watchedValueStr = if (watchedValues == 0) "" else s" and $watchedValues other values"

    logger.info(
      s"Watching for changes to $watchedPaths paths$watchedValueStr... (Enter to re-run, Ctrl-C to exit)"
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
