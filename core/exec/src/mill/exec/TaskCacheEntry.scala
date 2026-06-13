package mill.exec

import mill.api.daemon.internal.NonFatal
import mill.api.internal.{Cached, EnvSignature}
import mill.api.{ExecutionPaths, Logger, PathRef, Task, Val}

private[exec] final class TaskCacheEntry(
    paths: ExecutionPaths,
    classLoaderSigHash: Int
) {
  private def currentEnv: EnvSignature = EnvSignature.current(classLoaderSigHash)

  def write(json: ujson.Value, valueHash: Int, inputsHash: Int): Unit = {
    val env = currentEnv
    os.write.over(
      paths.meta,
      upickle.stream(
        Cached(
          json,
          valueHash,
          inputsHash,
          millVersion = env.millVersion,
          millJvmVersion = env.millJvmVersion,
          classLoaderSigHash = env.classLoaderSigHash
        ),
        indent = 4
      ),
      createFolders = true
    )
  }

  def read(
      logger: Logger,
      inputsHash: Int,
      labelled: Task.Named[?]
  ): Option[TaskCacheEntry.Loaded] = {
    for {
      cached <-
        // `.wrapped.toFile`: upickle/Files needs a real on-disk path; `.toIO` would yield
        // the relativized `../mill-workspace/...` alias form in reproducible mode.
        try Some(upickle.read[Cached](paths.meta.wrapped.toFile, trace = false))
        catch { case NonFatal(_) => None }
    } yield {
      val envDiffReasons =
        EnvSignature(cached.millVersion, cached.millJvmVersion, cached.classLoaderSigHash)
          .diffReasonsTo(currentEnv)

      TaskCacheEntry.Loaded(
        previousInputsHash = cached.inputsHash,
        valueOpt = for {
          _ <- Option.when(cached.inputsHash == inputsHash && envDiffReasons.isEmpty)(())
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
        valueHash = cached.valueHash,
        invalidationReason = envDiffReasons.headOption
      )
    }
  }
}

private[exec] object TaskCacheEntry {
  final case class Loaded(
      previousInputsHash: Int,
      valueOpt: Option[(Val, Seq[PathRef])],
      valueHash: Int,
      // The reason this entry's env signature differs from the current run (`name:OLD->NEW`), if
      // any; surfaced via `GroupExecution.Results` to the invalidation-tree log.
      invalidationReason: Option[String] = None
  )
}
