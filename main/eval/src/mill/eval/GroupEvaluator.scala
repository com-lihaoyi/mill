package mill.eval

import mill.api.Result.{OuterStack, Success}
import mill.api.Strict.Agg
import mill.api._
import mill.define._
import mill.eval.Evaluator.TaskResult
import mill.util._
import scala.collection.mutable

import scala.util.DynamicVariable
import scala.util.control.NonFatal

/**
 * Logic around evaluating a single group, which is a collection of [[Task]]s
 * with a single [[Terminal]].
 */
private[mill] trait GroupEvaluator extends EvaluatorCodeHashing with EvaluatorCaching {
  def home: os.Path
  def outPath: os.Path
  def externalOutPath: os.Path
  def rootModule: mill.define.BaseModule
  def classLoaderSigHash: Int

  def env: Map[String, String]
  def failFast: Boolean
  def threadCount: Option[Int]
  def scriptImportGraph: Map[os.Path, (Int, Seq[os.Path])]
  def methodCodeHashSignatures: Map[String, Int]
  def disableCallgraphInvalidation: Boolean

  val effectiveThreadCount: Int =
    this.threadCount.getOrElse(Runtime.getRuntime().availableProcessors())

  // those result which are inputs but not contained in this terminal group
  def evaluateGroupCached(
      terminal: Terminal,
      group: Agg[Task[_]],
      results: Map[Task[_], TaskResult[(Val, Int)]],
      counterMsg: String,
      zincProblemReporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: ColorLogger
  ): GroupEvaluator.Results = {

    val externalInputsHash = scala.util.hashing.MurmurHash3.orderedHash(
      group.items.flatMap(_.inputs).filter(!group.contains(_))
        .flatMap(results(_).result.asSuccess.map(_.value._2))
    )

    val sideHashes = scala.util.hashing.MurmurHash3.orderedHash(
      group.iterator.map(_.sideHash)
    )

    val scriptsHash = computeScriptHash(group)
    val inputsHash = externalInputsHash + sideHashes + classLoaderSigHash + scriptsHash

    terminal match {
      case Terminal.Task(task) =>
        val (newResults, newEvaluated) = evaluateGroup(
          group,
          results,
          inputsHash,
          paths = None,
          maybeTargetLabel = None,
          counterMsg = counterMsg,
          zincProblemReporter,
          testReporter,
          logger
        )
        GroupEvaluator.Results(newResults, newEvaluated.toSeq, null, inputsHash, -1)

      case labelled: Terminal.Labelled[_] =>
        val out =
          if (!labelled.task.ctx.external) outPath
          else externalOutPath

        val paths = EvaluatorPaths.resolveDestPaths(
          out,
          Terminal.destSegments(labelled)
        )

        val (finalCached, previousInputsHash) =
          handleCacheLoad(logger, inputsHash, labelled, paths)

        finalCached match {
          case Some((v, hashCode)) =>
            val res = Result.Success((v, hashCode))
            val newResults: Map[Task[_], TaskResult[(Val, Int)]] =
              Map(labelled.task -> TaskResult(res, () => res))

            GroupEvaluator.Results(
              newResults,
              Nil,
              cached = true,
              inputsHash,
              -1
            )

          case _ =>
            // uncached
            if (labelled.task.flushDest) os.remove.all(paths.dest)

            val targetLabel = Terminal.printTerm(terminal)

            val (newResults, newEvaluated) =
              GroupEvaluator.dynamicTickerPrefix.withValue(s"[$counterMsg] $targetLabel > ") {
                evaluateGroup(
                  group,
                  results,
                  inputsHash,
                  paths = Some(paths),
                  maybeTargetLabel = Some(targetLabel),
                  counterMsg = counterMsg,
                  zincProblemReporter,
                  testReporter,
                  logger
                )
              }

            newResults(labelled.task) match {
              case TaskResult(Result.Failure(_, Some((v, _))), _) =>
                handleTaskResult(v, v.##, paths, inputsHash, labelled)

              case TaskResult(Result.Success((v, _)), _) =>
                handleTaskResult(v, v.##, paths, inputsHash, labelled)

              case _ =>
                // Wipe out any cached meta.json file that exists, so
                // a following run won't look at the cached metadata file and
                // assume it's associated with the possibly-borked state of the
                // destPath after an evaluation failure.
                os.remove.all(paths.meta)
            }

            GroupEvaluator.Results(
              newResults,
              newEvaluated.toSeq,
              cached = if (labelled.task.isInstanceOf[InputImpl[_]]) null else false,
              inputsHash,
              previousInputsHash
            )
        }
    }
  }

  private def evaluateGroup(
      group: Agg[Task[_]],
      results: Map[Task[_], TaskResult[(Val, Int)]],
      inputsHash: Int,
      paths: Option[EvaluatorPaths],
      maybeTargetLabel: Option[String],
      counterMsg: String,
      reporter: Int => Option[CompileProblemReporter],
      testReporter: TestReporter,
      logger: mill.api.Logger
  ): (Map[Task[_], TaskResult[(Val, Int)]], mutable.Buffer[Task[_]]) = {

    def computeAll(enableTicker: Boolean) = {
      val newEvaluated = mutable.Buffer.empty[Task[_]]
      val newResults = mutable.Map.empty[Task[_], Result[(Val, Int)]]

      val nonEvaluatedTargets = group.indexed.filterNot(results.contains)

      // should we log progress?
      val logRun = maybeTargetLabel.isDefined && {
        val inputResults = for {
          target <- nonEvaluatedTargets
          item <- target.inputs.filterNot(group.contains)
        } yield results(item).map(_._1)
        inputResults.forall(_.result.isInstanceOf[Result.Success[_]])
      }

      val tickerPrefix = maybeTargetLabel.map { targetLabel =>
        val prefix = s"[$counterMsg] $targetLabel "
        if (logRun && enableTicker) logger.ticker(prefix)
        prefix + "| "
      }

      val multiLogger = new ProxyLogger(resolveLogger(paths.map(_.log), logger)) {
        override def ticker(s: String): Unit = {
          if (enableTicker) super.ticker(tickerPrefix.getOrElse("") + s)
          else () // do nothing
        }
      }
      // This is used to track the usage of `T.dest` in more than one Task
      // But it's not really clear what issue we try to prevent here
      // Vice versa, being able to use T.dest in multiple `T.task`
      // is rather essential to split up larger tasks into small parts
      // So I like to disable this detection for now
      var usedDest = Option.empty[(Task[_], Array[StackTraceElement])]
      for (task <- nonEvaluatedTargets) {
        newEvaluated.append(task)
        val targetInputValues = task.inputs
          .map { x => newResults.getOrElse(x, results(x).result) }
          .collect { case Result.Success((v, _)) => v }

        val res = {
          if (targetInputValues.length != task.inputs.length) Result.Skipped
          else {
            val args = new mill.api.Ctx(
              args = targetInputValues.map(_.value).toIndexedSeq,
              dest0 = () =>
                paths match {
                  case Some(dest) =>
                    if (usedDest.isEmpty) os.makeDir.all(dest.dest)
                    usedDest = Some((task, new Exception().getStackTrace))
                    dest.dest

                  case None => throw new Exception("No `dest` folder available here")
                },
              log = multiLogger,
              home = home,
              env = env,
              reporter = reporter,
              testReporter = testReporter,
              workspace = rootModule.millSourcePath
            ) with mill.api.Ctx.Jobs {
              override def jobs: Int = effectiveThreadCount
            }

            mill.api.SystemStreams.withStreams(multiLogger.systemStreams) {
              try task.evaluate(args).map(Val(_))
              catch {
                case NonFatal(e) =>
                  Result.Exception(
                    e,
                    new OuterStack(new Exception().getStackTrace.toIndexedSeq)
                  )
              }
            }
          }
        }

        newResults(task) =
          for (v <- res) yield (v, if (task.isInstanceOf[Worker[_]]) inputsHash else v.##)

      }
      multiLogger.close()
      (newResults, newEvaluated)
    }

    val (newResults, newEvaluated) = computeAll(enableTicker = true)

    if (!failFast) maybeTargetLabel.foreach { targetLabel =>
      val taskFailed = newResults.exists(task => !task._2.isInstanceOf[Success[_]])
      if (taskFailed) {
        logger.error(s"[${counterMsg}] ${targetLabel} failed")
      }
    }

    (
      newResults
        .map { case (k, v) =>
          val recalc = () => computeAll(enableTicker = false)._1.apply(k)
          val taskResult = TaskResult(v, recalc)
          (k, taskResult)
        }
        .toMap,
      newEvaluated
    )
  }

  def resolveLogger(logPath: Option[os.Path], logger: mill.api.Logger): mill.api.Logger =
    logPath match {
      case None => logger
      case Some(path) => new MultiLogger(
          logger.colored,
          logger,
          // we always enable debug here, to get some more context in log files
          new FileLogger(logger.colored, path, debugEnabled = true),
          logger.systemStreams.in,
          debugEnabled = logger.debugEnabled
        )
    }
}

private[mill] object GroupEvaluator {
  val dynamicTickerPrefix = new DynamicVariable("")

  case class Results(
      newResults: Map[Task[_], TaskResult[(Val, Int)]],
      newEvaluated: Seq[Task[_]],
      cached: java.lang.Boolean,
      inputsHash: Int,
      previousInputsHash: Int
  )
}
