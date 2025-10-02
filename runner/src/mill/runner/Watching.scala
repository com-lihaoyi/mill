package mill.runner

import mill.api.{SystemStreams, internal}
import mill.util.{Colors, Watchable}

import java.io.InputStream
import java.nio.channels.ClosedChannelException
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

  /**
   * @param useNotify whether to use filesystem based watcher. If it is false uses polling.
   * @param serverDir the directory for storing logs of the mill server
   */
  case class WatchArgs(
      setIdle: Boolean => Unit,
      colors: Colors,
      useNotify: Boolean,
      serverDir: os.Path
  )

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

          try {
            watchArgs.setIdle(true)
            enterKeyPressed = watchAndWait(streams, streams.in, watchables, watchArgs)
          } finally {
            watchArgs.setIdle(false)
          }
        }
        throw new IllegalStateException("unreachable")
    }
  }

  private def watchAndWait(
      streams: SystemStreams,
      stdin: InputStream,
      watched: Seq[Watchable],
      watchArgs: WatchArgs
  ): Boolean = {
    val (watchedPollables, watchedPathsSeq) = watched.partitionMap {
      case w: Watchable.Value => Left(w)
      case p: Watchable.Path => Right(p)
    }
    val watchedPathsSet = watchedPathsSeq.iterator.map(p => p.p.path).toSet
    val watchedValueCount = watched.size - watchedPathsSeq.size

    val watchedValueStr =
      if (watchedValueCount == 0) "" else s" and $watchedValueCount other values"

    streams.err.println {
      val viaFsNotify = if (watchArgs.useNotify) " (via fsnotify)" else ""
      watchArgs.colors.info(
        s"Watching for changes to ${watchedPathsSeq.size} paths$viaFsNotify$watchedValueStr... (Enter to re-run, Ctrl-C to exit)"
      ).toString
    }

    def doWatch(notifiablesChanged: () => Boolean) = {
      val enterKeyPressed = statWatchWait(watchedPollables, stdin, notifiablesChanged)
      enterKeyPressed
    }

    def doWatchPolling() =
      doWatch(notifiablesChanged = () => watchedPathsSeq.exists(p => !p.validate()))

    def doWatchFsNotify() = {
      Using.resource(os.write.outputStream(watchArgs.serverDir / "fsNotifyWatchLog")) { watchLog =>
        def writeToWatchLog(s: String): Unit = {
          try {
            watchLog.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            watchLog.write('\n')
          } catch {
            case _: ClosedChannelException => /* do nothing, the file is already closed */
          }
        }

        @volatile var pathChangesDetected = false

        // oslib watch only works with folders, so we have to watch the parent folders instead

        writeToWatchLog(
          s"[watched-paths:unfiltered] ${watchedPathsSet.toSeq.sorted.mkString("\n")}"
        )

        val workspaceRoot = mill.api.BuildCtx.workspaceRoot

        /** Paths that are descendants of [[workspaceRoot]]. */
        val pathsUnderWorkspaceRoot = watchedPathsSet.filter { path =>
          val isUnderWorkspaceRoot = path.startsWith(workspaceRoot)
          if (!isUnderWorkspaceRoot) {
            streams.err.println(watchArgs.colors.error(
              s"Watched path $path is outside workspace root $workspaceRoot, this is unsupported."
            ).toString())
          }

          isUnderWorkspaceRoot
        }

        // If I have 'root/a/b/c'
        //
        // Then I want to watch:
        //   root/a/b/c
        //   root/a/b
        //   root/a
        //   root
        //
        // We're only setting one `os.watch.watch` on the root, and this makes it sound like
        // we're setting multiple. What we're actually doing is choosing the paths we need to watch recursively in
        // Linux since inotify is non-recursive by default, since changes in any enclosing folder could result in the
        // watched file or folder disappearing (e.g. if the enclosing folder was renamed) and we want to pick up such
        // changes.
        val filterPaths = pathsUnderWorkspaceRoot.flatMap { path =>
          path.relativeTo(workspaceRoot).segments.inits.map(segments => workspaceRoot / segments)
        }
        writeToWatchLog(s"[watched-paths:filtered] ${filterPaths.toSeq.sorted.mkString("\n")}")

        Using.resource(os.watch.watch(
          // Just watch the root folder
          Seq(workspaceRoot),
          filter = path => {
            val shouldBeWatched =
              filterPaths.contains(path) || watchedPathsSet.exists(watchedPath =>
                path.startsWith(watchedPath)
              )
            writeToWatchLog(s"[filter] (shouldBeWatched=$shouldBeWatched) $path")
            shouldBeWatched
          },
          onEvent = changedPaths => {
            // Make sure that the changed paths are actually the ones in our watch list and not some adjacent files in the
            // same folder
            val hasWatchedPath =
              changedPaths.exists(p =>
                watchedPathsSet.exists(watchedPath => p.startsWith(watchedPath))
              )
            writeToWatchLog(
              s"[changed-paths] (hasWatchedPath=$hasWatchedPath) ${changedPaths.mkString("\n")}"
            )
            if (hasWatchedPath) {
              pathChangesDetected = true
            }
          },
          logger = (eventType, data) =>
            writeToWatchLog(s"[watch:event] $eventType: ${pprint.apply(data).plainText}")
        )) { _ =>
          // If already stale, re-evaluate instantly.
          //
          // We need to do this to prevent any changes from slipping through the gap between the last evaluation and
          // starting the watch.
          val alreadyStale = watched.exists(w => !w.validate())

          if (alreadyStale) false
          else doWatch(notifiablesChanged = () => pathChangesDetected)
        }
      }
    }

    if (watchArgs.useNotify) doWatchFsNotify()
    else doWatchPolling()
  }

  /**
   * @param notifiablesChanged returns true if any of the notifiables have changed
   *
   * @return `true` if enter key is pressed to re-run tasks explicitly, false if changes in watched files occured.
   */
  def statWatchWait(
      watched: Seq[Watchable],
      stdin: InputStream,
      notifiablesChanged: () => Boolean
  ): Boolean = {
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
