package mill.entrypoint

import mill.MillCliConfig
import mill.internal.Watchable

import java.io.InputStream
import scala.annotation.tailrec

object Watching{

  /**
   * Custom version of [[watchLoop]] that lets us generate the watched-file
   * signature only on demand, so if we don't have config.watch enabled we do
   * not pay the cost of generating it
   */
//  @tailrec def watchLoop2[T](config: MillCliConfig,
//                             ringBell: Boolean,
//                             setIdle: Boolean => Unit,
//                             isRepl: Boolean,
//                             printing: Boolean,
//                             run: Main => (Res[T], () => Seq[(mill.internal.Watchable, Long)])): Boolean = {
//    val (result, watched) = run(initMain(isRepl))
//
//    val success = handleWatchRes(result, printing)
//    if (ringBell) {
//      if (success) println("\u0007")
//      else {
//        println("\u0007")
//        Thread.sleep(250)
//        println("\u0007")
//      }
//    }
//    if (!config.watch.value) success
//    else {
//      Watching.watchAndWait(setIdle, watched())
//      watchLoop2(isRepl, printing, run)
//    }
//  }

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



  def handleWatchRes[T](res: Either[String, T], printing: Boolean) = {
    res match {
      case Right(value) => true
      case _ => handleWatchRes0(res, printing)
    }
  }


  def handleWatchRes0[T](res: Either[String, T], printing: Boolean) = {
    val success = res match {
      case Left(msg) =>
        System.err.println(msg)
        false

      case Right(value) =>
        if (printing && value != ()) println(pprint.PPrinter.BlackWhite(value))
        true
    }
    success
  }


}
