package mill.daemon

import mill.api.SystemStreams
import mill.api.daemon.Watchable
import mill.api.BuildCtx
import mill.internal.Colors

import java.io.InputStream
import java.nio.channels.ClosedChannelException
import scala.annotation.tailrec
import scala.util.Using

/**
 * Logic around the "watch and wait" functionality in Mill: re-run on change,
 * re-run when the user presses Enter, printing status messages, etc.
 */
object Watching {
  case class Result[T](watched: Seq[Watchable], error: Option[String], result: T)

  trait Evaluate[T] {
    def apply(skipSelectiveExecution: Boolean, previousState: Option[T]): Result[T]
  }

  /**
   * @param useNotify whether to use filesystem based watcher. If it is false uses polling.
   * @param daemonDir the directory for storing logs of the mill server
   */
  case class WatchArgs(
      setIdle: Boolean => Unit,
      colors: Colors,
      useNotify: Boolean,
      daemonDir: os.Path
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
        val Result(_, errorOpt, result) =
          evaluate(skipSelectiveExecution = false, previousState = None)
        handleError(errorOpt)
        (errorOpt.isEmpty, result)

      case Some(watchArgs) =>
        var prevState: Option[T] = None
        var skipSelectiveExecution = true // Always skip selective execution for first run

        // Exits when the thread gets interruped.
        while (true) {
          val Result(watchables, errorOpt, result) = evaluate(skipSelectiveExecution, prevState)
          prevState = Some(result)
          handleError(errorOpt)

          try {
            watchArgs.setIdle(true)
            skipSelectiveExecution = watchAndWait(
              watchables,
              watchArgs,
              () => Option.when(lookForEnterKey(streams.in))(()),
              "  (Enter to re-run, Ctrl-C to exit)",
              streams.err.println(_)
            ).isDefined
          } finally {
            watchArgs.setIdle(false)
          }
        }
        throw new IllegalStateException("unreachable")
    }
  }

  def watchAndWait[T](
      watched: Seq[Watchable],
      watchArgs: WatchArgs,
      sideChannel: () => Option[T],
      extraMessage: String,
      log: String => Unit
  ): Option[T] = {
    val (watchedValues, watchedPathsSeq) = watched.partitionMap {
      case v: Watchable.Value => Left(v)
      case p: Watchable.Path => Right(p)
    }
    val watchedPathsSet = watchedPathsSeq.iterator.map(p => os.Path(p.p)).toSet
    val watchedValueCount = watched.size - watchedPathsSeq.size

    val watchedValueStr =
      if (watchedValueCount == 0) "" else s" and $watchedValueCount other values"

    log {
      val viaFsNotify = if (watchArgs.useNotify) " (via fsnotify)" else ""
      watchArgs.colors.info(
        s"Watching for changes to ${watchedPathsSeq.size} paths$viaFsNotify$watchedValueStr...$extraMessage"
      ).toString
    }

    def doWatch(notifiablesChanged: () => Boolean) =
      statWatchWait(
        watchedValues,
        notifiablesChanged,
        sideChannel
      )

    def doWatchPolling() =
      doWatch(notifiablesChanged = () => watchedPathsSeq.exists(p => !haveNotChanged(p)))

    def doWatchFsNotify() = {
      val watchLogFile = watchArgs.daemonDir / "fsNotifyWatchLog"
      Using.resource(os.write.outputStream(watchLogFile)) { watchLog =>
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

        val workspaceRoot = BuildCtx.workspaceRoot

        /** Paths that are descendants of [[workspaceRoot]]. */
        val pathsUnderWorkspaceRoot = watchedPathsSet.filter { path =>
          val isUnderWorkspaceRoot = path.startsWith(workspaceRoot)
          if (!isUnderWorkspaceRoot) {
            log(watchArgs.colors.error(
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

            // Do not log if the only thing that changed was the watch log file itself.
            //
            // See https://github.com/com-lihaoyi/mill/issues/5843
            if (hasWatchedPath || changedPaths.exists(_ != watchLogFile)) {
              writeToWatchLog(
                s"[changed-paths] (hasWatchedPath=$hasWatchedPath) ${changedPaths.mkString("\n")}"
              )
            }

            if (hasWatchedPath) {
              pathChangesDetected = true
            }
          },
          logger = (eventType, data) => {
            val _ = eventType
            val _ = data
            // Uncommenting this causes indefinite loop as writing to watch log triggers an event, which then
            // gets written to the watch log, which then triggers an event, and so on.
            //
            // https://github.com/com-lihaoyi/mill/issues/5843
//            writeToWatchLog(s"[watch:event] $eventType: ${pprint.apply(data).plainText}")
          }
        )) { _ =>
          // If already stale, re-evaluate instantly.
          //
          // We need to do this to prevent any changes from slipping through the gap between the last evaluation and
          // starting the watch.
          val alreadyStale = watched.exists(w => !haveNotChanged(w))

          if (alreadyStale) None
          else doWatch(notifiablesChanged = () => pathChangesDetected)
        }
      }(using
        closable =>
          try closable.close()
          catch {
            case _: java.io.IOException =>
            // Not sure why this happens, but if it does happen it probably means the
            // file handle has already been closed, so just continue on without crashing
          }
      )
    }

    if (watchArgs.useNotify) doWatchFsNotify()
    else doWatchPolling()
  }

  /**
   * @param notifiablesChanged returns true if any of the notifiables have changed
   *
   * @return `Some(...)` if notifiablesChanged returned a `Some(...)`, `None` if changes in watched files occured.
   */
  def statWatchWait[T](
      watchedValues: Seq[Watchable.Value],
      notifiablesChanged: () => Boolean,
      sideChannel: () => Option[T]
  ): Option[T] = {

    @tailrec def statWatchWait0(): Option[T] = {
      if (!notifiablesChanged() && watchedValues.forall(haveNotChanged)) {
        sideChannel() match {
          case Some(t) => Some(t)
          case None =>
            Thread.sleep(100)
            statWatchWait0()
        }
      } else
        None
    }

    statWatchWait0()
  }

  private def lookForEnterKey(stdin: InputStream): Boolean = {
    val buffer = new Array[Byte](4 * 1024)

    @tailrec def loop(): Boolean = {
      if (stdin.available() == 0) false
      else stdin.read(buffer) match {
        case 0 | -1 => false
        case bytesRead =>
          buffer.indexOf('\n') match {
            case -1 => loop()
            case index =>
              // If we found the newline further than the bytes read, that means it's not from this read and thus we
              // should try reading again.
              if (index >= bytesRead) loop()
              else true
          }
      }
    }

    loop()
  }

  /** @return true if the watchable did not change. */
  def haveNotChanged(w: Watchable): Boolean =
    mill.api.internal.WatchSig.haveNotChanged(w)

  def poll(w: Watchable): Long =
    mill.api.internal.WatchSig.poll(w)

  def signature(w: Watchable): Long =
    mill.api.internal.WatchSig.signature(w)
}
