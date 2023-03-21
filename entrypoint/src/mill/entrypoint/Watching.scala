package mill.entrypoint

import mill.MillCliConfig
import mill.internal.Watchable

import java.io.InputStream
import scala.annotation.tailrec

object Watching{

  def watchAndWait(setIdle: Boolean => Unit, stdin: InputStream, watched: Seq[(mill.internal.Watchable, Long)]) = {
    setIdle(true)
    watchAndWait0(stdin, watched)
    setIdle(false)
  }

  def watchAndWait0(stdin: InputStream, watched: Seq[(Watchable, Long)]) = {
    val watchedPaths = watched.count {
      case (Watchable.Path(p), _) => true
      case (_, _) => false
    }
    val watchedValues = watched.size - watchedPaths

    val watchedValueStr = if (watchedValues == 0) "" else s" and $watchedValues other values"

    println(
      s"Watching for changes to $watchedPaths paths$watchedValueStr... (Enter to re-run, Ctrl-C to exit)"
    )

    statWatchWait(watched, stdin)
  }

  def statWatchWait(watched: Seq[(Watchable, Long)],
                    stdin: InputStream): Unit = {
    val buffer = new Array[Byte](4 * 1024)

    def allWatchedUnchanged() =
      watched.forall { case (file, lastMTime) => file.poll() == lastMTime }

    @tailrec def statWatchWait0(): Unit = {
      if (allWatchedUnchanged()) {
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
