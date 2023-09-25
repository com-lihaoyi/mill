package mill.eval

import mill.api.{PathRef, Val}
import mill.define.Segments
import mill.util.ColorLogger

import scala.collection.mutable
import scala.util.control.NonFatal

trait EvaluatorCaching {

  def remoteCacheUrl: Option[String]
  def remoteCacheSalt: Option[String]
  def remoteCacheFilter: Option[Segments]
  def workerCache: mutable.Map[Segments, (Int, Val)]
  def classLoaderIdentityHash: Int

  def remoteCacheEnabled(labelled: Terminal.Labelled[_]) = {
    labelled.task.asTarget.nonEmpty &&
    remoteCacheFilter.fold(true)(Segments.checkPatternMatch(_, labelled.segments))
  }

  def handleCacheLoad(
      logger: ColorLogger,
      inputsHash: Int,
      labelled: Terminal.Labelled[_],
      paths: EvaluatorPaths
  ): (Option[(Val, Int)], Int) = {
    val cached = loadCachedJson(logger, inputsHash, labelled, paths.meta.toIO)
    lazy val remoteCached =
      if (remoteCacheEnabled(labelled)) {
        remoteCacheUrl.flatMap { url =>
          val remoteCacheLoaded = BazelRemoteCache.load(inputsHash, labelled, paths, url, remoteCacheSalt)
          if (!remoteCacheLoaded) None
          else {
            val (res, pathRefs) = PathRef.gatherSerializedPathRefs {
              loadCachedJson(logger, inputsHash, labelled, paths.meta.toIO)
            }
            if (pathRefs.forall(pr => os.exists(pr.path))) res
            else None
          }
        }
      } else None

    val upToDateWorker = loadUpToDateWorker(logger, inputsHash, labelled)

    val finalCached =
      upToDateWorker.map((_, inputsHash)) orElse
        cached.flatMap(_._2) orElse
        remoteCached.flatMap(_._2)

    val previousInputsHash = cached.map(_._1).getOrElse(-1)
    (finalCached, previousInputsHash)
  }

  def loadCachedJson(
      logger: ColorLogger,
      inputsHash: Int,
      labelled: Terminal.Labelled[_],
      readable: ujson.Readable
  ): Option[(Int, Option[(Val, Int)])] = {
    val cachedOpt =
      try Some(upickle.default.read[Evaluator.Cached](readable))
      catch {
        case NonFatal(_) => None
      }

    cachedOpt.map(loadCachedJson0(logger, inputsHash, labelled, _))
  }

  def loadCachedJson0(
      logger: ColorLogger,
      inputsHash: Int,
      labelled: Terminal.Labelled[_],
      cached: Evaluator.Cached
  ): (Int, Option[(Val, Int)]) = {
    (
      cached.inputsHash,
      for {
        _ <- Option.when(cached.inputsHash == inputsHash)(())
        reader <- labelled.task.readWriterOpt
        parsed <-
          try Some(upickle.default.read(cached.value)(reader))
          catch {
            case e: PathRef.PathRefValidationException =>
              logger.debug(
                s"${labelled.segments.render}: re-evaluating; ${e.getMessage}"
              )
              None
            case NonFatal(_) => None
          }
      } yield (Val(parsed), cached.valueHash)
    )
  }

  private def loadUpToDateWorker(
      logger: ColorLogger,
      inputsHash: Int,
      labelled: Terminal.Labelled[_]
  ): Option[Val] = {
    labelled.task.asWorker
      .flatMap { w =>
        workerCache.synchronized {
          workerCache.get(w.ctx.segments)
        }
      }
      .flatMap {
        case (cachedHash, upToDate)
            if cachedHash == workerCacheHash(inputsHash) =>
          Some(upToDate) // worker cached and up-to-date

        case (_, Val(obsolete: AutoCloseable)) =>
          // worker cached but obsolete, needs to be closed
          try {
            logger.debug(s"Closing previous worker: ${labelled.segments.render}")
            obsolete.close()
          } catch {
            case NonFatal(e) =>
              logger.error(
                s"${labelled.segments.render}: Errors while closing obsolete worker: ${e.getMessage()}"
              )
          }
          // make sure, we can no longer re-use a closed worker
          labelled.task.asWorker.foreach { w =>
            workerCache.synchronized {
              workerCache.remove(w.ctx.segments)
            }
          }
          None

        case _ => None // worker not cached or obsolete
      }
  }

  // Include the classloader identity hash as part of the worker hash. This is
  // because unlike other targets, workers are long-lived in memory objects,
  // and are not re-instantiated every run. Thus we need to make sure we
  // invalidate workers in the scenario where a the worker classloader is
  // re-created - so the worker *class* changes - but the *value* inputs to the
  // worker does not change. This typically happens when the worker class is
  // brought in via `import $ivy`, since the class then comes from the
  // non-bootstrap classloader which can be re-created when the `build.sc` file
  // changes.
  //
  // We do not want to do this for normal targets, because those are always
  // read from disk and re-instantiated every time, so whether the
  // classloader/class is the same or different doesn't matter.
  def workerCacheHash(inputHash: Int) = inputHash + classLoaderIdentityHash

  def handleTaskResult(
      v: Val,
      hashCode: Int,
      paths: EvaluatorPaths,
      inputsHash: Int,
      labelled: Terminal.Labelled[_]
  ): Unit = {
    labelled.task.asWorker match {
      case Some(w) =>
        workerCache.synchronized {
          workerCache.update(w.ctx.segments, (workerCacheHash(inputsHash), v))
        }
      case None =>
        val (terminalResult, pathRefs) = PathRef.gatherSerializedPathRefs {
          labelled
            .task
            .writerOpt
            .asInstanceOf[Option[upickle.default.Writer[Any]]]
            .map { w => upickle.default.writeJs(v.value)(w) }
        }

        for (json <- terminalResult) {
          val cached = Evaluator.Cached(json, hashCode, inputsHash)
          os.write.over(
            paths.meta,
            upickle.default.stream(cached, indent = 4),
            createFolders = true
          )

          for (url <- remoteCacheUrl if remoteCacheEnabled(labelled)) {
            BazelRemoteCache.store(paths, inputsHash, labelled, url, remoteCacheSalt, pathRefs)
          }
        }
    }
  }

}
