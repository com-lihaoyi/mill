package mill.daemon

import mill.api.SystemStreams
import mill.api.internal.internal
import mill.define.PathRef
import mill.define.internal.Watchable
import mill.internal.Colors

import java.io.InputStream
import scala.annotation.tailrec
import scala.util.Using

/**
 * Logic around the "watch and wait" functionality in Mill: re-run on change,
 * re-run when the user presses Enter, printing status messages, etc.
 */
@internal
object Watching {
  case class Result[T](watched: Seq[Watchable], error: Option[String], result: T)

  trait Evaluate[T] {
    def apply(enterKeyPressed: Boolean, previousState: Option[T]): Result[T]
  }

  case class WatchArgs(
      setIdle: Boolean => Unit,
      colors: Colors
  )

  /**
   * @param ringBell whether to emit bells
   * @param watch if [[None]] just runs once and returns
   */
  def watchLoop[T](
      ringBell: Boolean,
      watch: Option[WatchArgs],
      streams: SystemStreams,
      evaluate: Evaluate[T]
  ): (Boolean, T) = {
    def handleError(errorOpt: Option[String]): Unit = {
      errorOpt.foreach(streams.err.println)
      doRingBell(hasError = errorOpt.isDefined)
    }

    def doRingBell(hasError: Boolean): Unit = {
      if (!ringBell) return

      println("\u0007")
      if (hasError) {
        // If we have an error ring the bell again
        Thread.sleep(250)
        println("\u0007")
      }
    }

    watch match {
      case None =>
        val Result(watchables, errorOpt, result) =
          evaluate(enterKeyPressed = false, previousState = None)
        handleError(errorOpt)
        (errorOpt.isEmpty, result)

      case Some(watchArgs) =>
        var prevState: Option[T] = None
        var enterKeyPressed = false

        // Exits when the thread gets interruped.
        while (true) {
          val Result(watchables, errorOpt, result) = evaluate(enterKeyPressed, prevState)
          prevState = Some(result)
          handleError(errorOpt)

          // Do not enter watch if already stale, re-evaluate instantly.
          val alreadyStale = watchables.exists(w => !validateAnyWatchable(w))
          if (alreadyStale) {
            enterKeyPressed = false
          } else {
            enterKeyPressed =
              watchAndWait(streams, watchArgs.setIdle, streams.in, watchables, watchArgs.colors)
          }
        }
        throw new IllegalStateException("unreachable")
    }
  }

  def watchAndWait(
      streams: SystemStreams,
      setIdle: Boolean => Unit,
      stdin: InputStream,
      watched: Seq[Watchable],
      colors: Colors
  ): Boolean = {
    setIdle(true)
    val (watchedPollables, watchedPathsSeq) = watched.partitionMap {
      case w: Watchable.Pollable => Left(w)
      case p: Watchable.Path => Right(p)
    }
    val watchedPathsSet = watchedPathsSeq.iterator.map(p => os.Path(p.p)).toSet
    val watchedValueCount = watched.size - watchedPathsSeq.size

    val watchedValueStr =
      if (watchedValueCount == 0) "" else s" and $watchedValueCount other values"

    streams.err.println(
      colors.info(
        s"Watching for changes to ${watchedPathsSeq.size} paths$watchedValueStr... (Enter to re-run, Ctrl-C to exit)"
      ).toString
    )

    @volatile var pathChangesDetected = false

    // oslib watch only works with folders, so we have to watch the parent folders instead
    val osLibWatchPaths = watchedPathsSet.iterator.map(p => p / "..").toSet
//    mill.constants.DebugLog(
//      colors.info(s"[watch:watched-paths] ${osLibWatchPaths.mkString("\n")}").toString
//    )

    Using.resource(os.watch.watch(
      osLibWatchPaths.toSeq,
      onEvent = changedPaths => {
        // Make sure that the changed paths are actually the ones in our watch list and not some adjacent files in the
        // same folder
        val hasWatchedPath = changedPaths.exists(p => watchedPathsSet.contains(p))
//        mill.constants.DebugLog(colors.info(
//          s"[watch:changed-paths] (hasWatchedPath=$hasWatchedPath) ${changedPaths.mkString("\n")}"
//        ).toString)
        if (hasWatchedPath) {
          pathChangesDetected = true
        }
      },
//      logger = (eventType, data) => {
//        mill.constants.DebugLog(colors.info(s"[watch] $eventType: ${pprint.apply(data)}").toString)
//      }
    )) { _ =>
      val enterKeyPressed =
        statWatchWait(watchedPollables, stdin, notifiablesChanged = () => pathChangesDetected)
      setIdle(false)
      enterKeyPressed
    }
  }

  /**
   * @param notifiablesChanged returns true if any of the notifiables have changed
   *
   * @return `true` if enter key is pressed to re-run tasks explicitly, false if changes in watched files occured.
   */
  def statWatchWait(
      watched: Seq[Watchable.Pollable],
      stdin: InputStream,
      notifiablesChanged: () => Boolean
  ): Boolean = {
    val buffer = new Array[Byte](4 * 1024)

    @tailrec def statWatchWait0(): Boolean = {
      if (!notifiablesChanged() && watched.forall(w => validate(w))) {
        if (lookForEnterKey()) {
          true
        } else {
          Thread.sleep(100)
          statWatchWait0()
        }
      } else false
    }

    @tailrec def lookForEnterKey(): Boolean = {
      if (stdin.available() == 0) false
      else stdin.read(buffer) match {
        case 0 | -1 => false
        case bytesRead =>
          buffer.indexOf('\n') match {
            case -1 => lookForEnterKey()
            case index =>
              // If we found the newline further than the bytes read, that means it's not from this read and thus we
              // should try reading again.
              if (index >= bytesRead) lookForEnterKey()
              else true
          }
      }
    }

    statWatchWait0()
  }

  /** @return true if the watchable did not change. */
  inline def validate(w: Watchable.Pollable): Boolean = validateAnyWatchable(w)

  /**
   * As [[validate]] but accepts any [[Watchable]] for the cases when we do not want to use a notification system.
   *
   * Normally you should use [[validate]] so that types would guide your implementation.
   */
  def validateAnyWatchable(w: Watchable): Boolean = poll(w) == signature(w)

  def poll(w: Watchable): Long = w match {
    case Watchable.Path(p, quick, sig) =>
      new PathRef(os.Path(p), quick, sig, PathRef.Revalidate.Once).recomputeSig()
    case Watchable.Value(f, sig, pretty) => f()
  }

  def signature(w: Watchable): Long = w match {
    case Watchable.Path(p, quick, sig) =>
      new PathRef(os.Path(p), quick, sig, PathRef.Revalidate.Once).sig
    case Watchable.Value(f, sig, pretty) => sig
  }
}
