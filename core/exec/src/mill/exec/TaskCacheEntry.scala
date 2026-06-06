package mill.exec

import mill.api.daemon.internal.NonFatal
import mill.api.internal.Cached
import mill.api.{ExecutionPaths, Logger, PathRef, Task, Val}

private[exec] final class TaskCacheEntry(
    paths: ExecutionPaths,
    classLoaderSigHash: Int
) {
  def write(json: ujson.Value, valueHash: Int, inputsHash: Int): Unit =
    os.write.over(
      paths.meta,
      upickle.stream(
        Cached(
          json,
          valueHash,
          inputsHash,
          millVersion = mill.constants.BuildInfo.millVersion,
          millJvmVersion = sys.props("java.version"),
          classLoaderSigHash = classLoaderSigHash
        ),
        indent = 4
      ),
      createFolders = true
    )

  def read(
      logger: Logger,
      inputsHash: Int,
      labelled: Task.Named[?],
      versionMismatchReasons: java.util.concurrent.ConcurrentHashMap[Task[?], String]
  ): Option[TaskCacheEntry.Loaded] = {
    for {
      cached <-
        // `.wrapped.toFile`: upickle/Files needs a real on-disk path; `.toIO` would yield
        // the relativized `../mill-workspace/...` alias form in reproducible mode.
        try Some(upickle.read[Cached](paths.meta.wrapped.toFile, trace = false))
        catch { case NonFatal(_) => None }
    } yield {
      def checkMatch[T](cachedValue: T, currentValue: T, reasonName: String): Boolean = {
        val matches = cachedValue == currentValue
        if (!matches) {
          versionMismatchReasons.putIfAbsent(labelled, s"$reasonName:$cachedValue->$currentValue")
        }
        matches
      }

      val millVersionMatches =
        checkMatch(cached.millVersion, mill.constants.BuildInfo.millVersion, "mill-version-changed")
      val jvmVersionMatches =
        checkMatch(cached.millJvmVersion, sys.props("java.version"), "mill-jvm-version-changed")
      val classLoaderMatches =
        checkMatch(cached.classLoaderSigHash, classLoaderSigHash, "classpath-changed")

      TaskCacheEntry.Loaded(
        previousInputsHash = cached.inputsHash,
        valueOpt = for {
          _ <- Option.when(
            cached.inputsHash == inputsHash && millVersionMatches && jvmVersionMatches && classLoaderMatches
          )(())
          reader <- labelled.readWriterOpt
          (parsed, serializedPaths) <-
            try Some(PathRef.withSerializedPaths(upickle.read(cached.value, trace = false)(using
                reader
              )))
            catch {
              case e: PathRef.PathRefValidationException =>
                logger.debug(s"$labelled: re-evaluating; ${e.getMessage}")
                None
              case NonFatal(_) => None
            }
        } yield (Val(parsed), serializedPaths),
        valueHash = cached.valueHash
      )
    }
  }
}

private[exec] object TaskCacheEntry {
  final case class Loaded(
      previousInputsHash: Int,
      valueOpt: Option[(Val, Seq[PathRef])],
      valueHash: Int
  )
}
