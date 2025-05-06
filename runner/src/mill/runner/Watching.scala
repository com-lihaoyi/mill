package mill.runner

import mill.api.internal
import mill.util.{Colors, Watchable}
import mill.api.SystemStreams
import mill.main.client.DebugLog

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

  def watchLoop[T](
      ringBell: Boolean,
      watch: Option[WatchArgs],
      streams: SystemStreams,
      evaluate: Evaluate[T],
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
          val alreadyStale = watchables.exists(w => !w.validate())
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
      case w: Watchable.Value => Left(w)
      case p: Watchable.Path => Right(p)
    }
    val watchedPathsSet = watchedPathsSeq.iterator.map(p => p.p.path).toSet
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

    mill.main.client.DebugLog.println(
      colors.info(s"[watch:watched-paths:unfiltered] ${watchedPathsSet.toSeq.sorted.mkString("\n")}").toString
    )

    val ignoredFolders = Seq(
      mill.api.WorkspaceRoot.workspaceRoot / "out",
      mill.api.WorkspaceRoot.workspaceRoot / ".bloop",
      mill.api.WorkspaceRoot.workspaceRoot / ".metals",
      mill.api.WorkspaceRoot.workspaceRoot / ".idea",
      mill.api.WorkspaceRoot.workspaceRoot / ".git",
      mill.api.WorkspaceRoot.workspaceRoot / ".bsp",
    )
    mill.main.client.DebugLog.println(
      colors.info(s"[watch:ignored-paths] ${ignoredFolders.toSeq.sorted.mkString("\n")}").toString
    )

    val osLibWatchPaths = watchedPathsSet.iterator.map(p => p / "..").toSet
    mill.main.client.DebugLog.println(
      colors.info(s"[watch:watched-paths] ${osLibWatchPaths.toSeq.sorted.mkString("\n")}").toString
    )

    Using.resource(os.watch.watch(
      osLibWatchPaths.toSeq,
//      filter = path => {
//        val shouldBeIgnored = ignoredFolders.exists(ignored => path.startsWith(ignored))
//        mill.main.client.DebugLog.println(
//          colors.info(s"[watch:filter] $path (ignored=$shouldBeIgnored), ignoredFolders=${ignoredFolders.mkString("[\n  ", "\n  ", "\n]")}").toString
//        )
//        !shouldBeIgnored
//      },
      onEvent = changedPaths => {
        // Make sure that the changed paths are actually the ones in our watch list and not some adjacent files in the
        // same folder
        val hasWatchedPath = changedPaths.exists(p => watchedPathsSet.exists(watchedPath => p.startsWith(watchedPath)))
        mill.main.client.DebugLog.println(colors.info(
          s"[watch:changed-paths] (hasWatchedPath=$hasWatchedPath) ${changedPaths.mkString("\n")}"
        ).toString)
        if (hasWatchedPath) {
          pathChangesDetected = true
        }
      },
      logger = (eventType, data) => {
        mill.main.client.DebugLog.println(colors.info(s"[watch] $eventType: ${pprint.apply(data)}").toString)
      }
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
  def statWatchWait(watched: Seq[Watchable], stdin: InputStream, notifiablesChanged: () => Boolean): Boolean = {
    val buffer = new Array[Byte](4 * 1024)

    @tailrec def statWatchWait0(): Boolean = {
      if (!notifiablesChanged() && watched.forall(_.validate())) {
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
}
