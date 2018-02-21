package mill.eval

import java.net.URLClassLoader

import mill.util.Router.EntryPoint
import ammonite.ops._
import ammonite.runtime.SpecialClassLoader
import mill.define.{Ctx => _, _}
import mill.eval.Result.OuterStack
import mill.util
import mill.util._
import mill.util.Strict.Agg

import scala.collection.mutable
import scala.util.control.NonFatal
case class Labelled[T](task: NamedTask[T],
                       segments: Segments){
  def format = task match{
    case t: Target[T] => Some(t.readWrite.asInstanceOf[upickle.default.ReadWriter[T]])
    case _ => None
  }
  def writer = task match{
    case t: mill.define.Command[T] => Some(t.writer.asInstanceOf[upickle.default.Writer[T]])
    case t: Target[T] => Some(t.readWrite.asInstanceOf[upickle.default.ReadWriter[T]])
    case _ => None
  }
}
case class Evaluator[T](outPath: Path,
                        externalOutPath: Path,
                        rootModule: mill.define.BaseModule,
                        log: Logger,
                        classLoaderSig: Seq[(Path, Long)] = Evaluator.classLoaderSig,
                        workerCache: mutable.Map[Segments, (Int, Any)] = mutable.Map.empty){

  def evaluate(goals: Agg[Task[_]]): Evaluator.Results = {
    mkdir(outPath)

    val transitive = Graph.transitiveTargets(goals)
    val topoSorted = Graph.topoSorted(transitive)
    val sortedGroups = Graph.groupAroundImportantTargets(topoSorted){
      case t: NamedTask[Any]   =>
        val segments = t.ctx.segments
        val finalTaskOverrides = t match{
          case t: Target[_] =>
            rootModule.millInternal.segmentsToTargets.get(segments).fold(0)(_.ctx.overrides)

          case c: mill.define.Command[_] =>
            def findMatching(cls: Class[_]): Option[Seq[(Int, EntryPoint[_])]] = {
              rootModule.millDiscover.value.get(cls) match{
                case Some(v) => Some(v)
                case None =>
                  cls.getSuperclass match{
                    case null => None
                    case superCls => findMatching(superCls)
                  }
              }
            }

            findMatching(c.cls) match{
              case Some(v) =>
                v.find(_._2.name == c.ctx.segment.pathSegments.head).get._1
              // For now we don't properly support overrides for external modules
              // that do not appear in the Evaluator's main Discovered listing
              case None => 0
            }

          case c: mill.define.Worker[_] => 0
        }

        val additional =
          if (finalTaskOverrides == t.ctx.overrides) Nil
          else Seq(Segment.Label("overriden")) ++ t.ctx.enclosing.split('.').map(Segment.Label)

        Right(Labelled(t, segments ++ additional))
      case t if goals.contains(t) => Left(t)
    }

    val evaluated = new Agg.Mutable[Task[_]]
    val results = mutable.LinkedHashMap.empty[Task[_], Result[(Any, Int)]]

    for (((terminal, group), i) <- sortedGroups.items().zipWithIndex){
      // Increment the counter message by 1 to go from 1/10 to 10/10 instead of 0/10 to 9/10
      val counterMsg = (i+1) + "/" + sortedGroups.keyCount
      val (newResults, newEvaluated) = evaluateGroupCached(terminal, group, results, counterMsg)
      for(ev <- newEvaluated){
        evaluated.append(ev)
      }
      for((k, v) <- newResults) {
        results.put(k, v)
      }

    }

    val failing = new util.MultiBiMap.Mutable[Either[Task[_], Labelled[_]], Result.Failing[_]]
    for((k, vs) <- sortedGroups.items()){
      failing.addAll(
        k,
        vs.items.flatMap(results.get).collect{case f: Result.Failing[_] => f.map(_._1)}
      )
    }
    Evaluator.Results(
      goals.indexed.map(results(_).map(_._1)),
      evaluated,
      transitive,
      failing,
      results.map{case (k, v) => (k, v.map(_._1))}
    )
  }


  def evaluateGroupCached(terminal: Either[Task[_], Labelled[_]],
                          group: Agg[Task[_]],
                          results: collection.Map[Task[_], Result[(Any, Int)]],
                          counterMsg: String): (collection.Map[Task[_], Result[(Any, Int)]], Seq[Task[_]]) = {


    val externalInputs = group.items.flatMap(_.inputs).filter(!group.contains(_)).toVector

    val inputsHash =
      externalInputs.map(results(_).map(_._2)).hashCode +
      group.toIterator.map(_.sideHash).toVector.hashCode() +
      classLoaderSig.hashCode()

    terminal match{
      case Left(task) =>
        evaluateGroup(
          group,
          results,
          inputsHash,
          paths = None,
          maybeTargetLabel = None,
          counterMsg = counterMsg
        )
      case Right(labelledNamedTask) =>

        val paths = Evaluator.resolveDestPaths(
          if (!labelledNamedTask.task.ctx.external) outPath else externalOutPath,
          labelledNamedTask.segments
        )

        mkdir(paths.out)
        val cached = for{
          json <- scala.util.Try(upickle.json.read(read(paths.meta))).toOption
          (cachedHash, terminalResult) <- scala.util.Try(upickle.default.readJs[(Int, upickle.Js.Value)](json)).toOption
          if cachedHash == inputsHash
          reader <- labelledNamedTask.format
          parsed <- reader.read.lift(terminalResult)
        } yield parsed

        val workerCached = labelledNamedTask.task.asWorker
          .flatMap{w => workerCache.get(w.ctx.segments)}
          .collect{case (`inputsHash`, v) => v}

        workerCached.map((_, inputsHash)) orElse cached.map(p => (p, p.hashCode())) match{
          case Some((v, hashCode)) =>
            val newResults = mutable.LinkedHashMap.empty[Task[_], Result[(Any, Int)]]
            newResults(labelledNamedTask.task) = Result.Success((v, hashCode))

            (newResults, Nil)

          case _ =>

            val Seq(first, rest @_*) = labelledNamedTask.segments.value
            val msgParts = Seq(first.asInstanceOf[Segment.Label].value) ++ rest.map{
              case Segment.Label(s) => "." + s
              case Segment.Cross(s) => "[" + s.mkString(",") + "]"
            }

            if (labelledNamedTask.task.flushDest) rm(paths.dest)

            val (newResults, newEvaluated) = evaluateGroup(
              group,
              results,
              inputsHash,
              paths = Some(paths),
              maybeTargetLabel = Some(msgParts.mkString),
              counterMsg = counterMsg
            )

            newResults(labelledNamedTask.task) match{
              case Result.Failure(_, Some((v, hashCode))) =>
                labelledNamedTask.task.asWorker match{
                  case Some(w) =>
                    workerCache(w.ctx.segments) = (inputsHash, v)
                  case None =>
                    val terminalResult = labelledNamedTask
                      .writer
                      .asInstanceOf[Option[upickle.default.Writer[Any]]]
                      .map(_.write(v))

                    for(t <- terminalResult){
                      write.over(paths.meta, upickle.default.write(inputsHash -> t, indent = 4))
                    }
                }

              case Result.Success((v, hashCode)) =>
                labelledNamedTask.task.asWorker match{
                  case Some(w) =>
                    workerCache(w.ctx.segments) = (inputsHash, v)
                  case None =>
                    val terminalResult = labelledNamedTask
                      .writer
                      .asInstanceOf[Option[upickle.default.Writer[Any]]]
                      .map(_.write(v))

                    for(t <- terminalResult){
                      write.over(paths.meta, upickle.default.write(inputsHash -> t, indent = 4))
                    }
                }
              case _ =>
                // Wipe out any cached meta.json file that exists, so
                // a following run won't look at the cached metadata file and
                // assume it's associated with the possibly-borked state of the
                // destPath after an evaluation failure.
                rm(paths.meta)
            }



            (newResults, newEvaluated)
        }
    }
  }


  def evaluateGroup(group: Agg[Task[_]],
                    results: collection.Map[Task[_], Result[(Any, Int)]],
                    inputsHash: Int,
                    paths: Option[Evaluator.Paths],
                    maybeTargetLabel: Option[String],
                    counterMsg: String) = {


    val newEvaluated = mutable.Buffer.empty[Task[_]]
    val newResults = mutable.LinkedHashMap.empty[Task[_], Result[(Any, Int)]]

    val nonEvaluatedTargets = group.indexed.filterNot(results.contains)

    maybeTargetLabel.foreach { targetLabel =>
      val inputResults = for {
        target <- nonEvaluatedTargets
        item <- target.inputs.filterNot(group.contains)
      } yield results(item).map(_._1)

      val logRun = inputResults.forall(_.isInstanceOf[Result.Success[_]])

      if(logRun) { log.ticker(s"[$counterMsg] $targetLabel ") }
    }

    val multiLogger = resolveLogger(paths.map(_.log))
    var usedDest = Option.empty[(Task[_], Array[StackTraceElement])]
    for (task <- nonEvaluatedTargets) {
      val currentStack = new Exception().getStackTrace
      newEvaluated.append(task)
      val targetInputValues = task.inputs
        .map(x => newResults.getOrElse(x, results(x)))
        .collect{ case Result.Success((v, hashCode)) => v }

      val res =
        if (targetInputValues.length != task.inputs.length) Result.Skipped
        else {
          val args = new Ctx(
            targetInputValues.toArray[Any],
            () => usedDest match{
              case Some((earlierTask, earlierStack)) if earlierTask != task =>
                val inner = new Exception("Earlier usage of `dest`")
                inner.setStackTrace(earlierStack)
                throw new Exception(
                  "`dest` can only be used in one place within each Target[T]",
                  inner
                )
              case _ =>
                usedDest = Some((
                  task,
                  new Exception().getStackTrace.dropRight(currentStack.length)
                ))
                paths match{
                  case Some(dest) =>
                    mkdir(dest.dest)
                    dest.dest
                  case None =>
                    throw new Exception("No `dest` folder available here")
                }
            },
            multiLogger
          )

          val out = System.out
          val err = System.err
          try{
            System.setErr(multiLogger.errorStream)
            System.setOut(multiLogger.outputStream)
            Console.withOut(multiLogger.outputStream){
              Console.withErr(multiLogger.errorStream){
                task.evaluate(args)
              }
            }
          }catch{ case NonFatal(e) =>

            Result.Exception(e, new OuterStack(currentStack))
          }finally{
            System.setErr(err)
            System.setOut(out)
          }
        }

      newResults(task) = for(v <- res) yield {
        (v,
          if (task.isInstanceOf[Worker[_]]) inputsHash
          else v.hashCode
        )
      }
    }

    multiLogger.close()

    (newResults, newEvaluated)
  }

  def resolveLogger(logPath: Option[Path]): Logger = logPath match{
    case None => log
    case Some(path) =>
      rm(path)
      MultiLogger(log.colored, log, FileLogger(log.colored, path))
  }
}


object Evaluator{
  case class State(rootModule: mill.define.BaseModule,
                   classLoaderSig: Seq[(Path, Long)],
                   workerCache: mutable.Map[Segments, (Int, Any)],
                   watched: Seq[(Path, Long)])
  // This needs to be a ThreadLocal because we need to pass it into the body of
  // the TargetScopt#read call, which does not accept additional parameters.
  // Until we migrate our CLI parsing off of Scopt (so we can pass the BaseModule
  // in directly) we are forced to pass it in via a ThreadLocal
  val currentEvaluator = new ThreadLocal[mill.eval.Evaluator[_]]

  case class Paths(out: Path,
                   dest: Path,
                   meta: Path,
                   log: Path)
  def makeSegmentStrings(segments: Segments) = segments.value.flatMap{
    case Segment.Label(s) => Seq(s)
    case Segment.Cross(values) => values.map(_.toString)
  }
  def resolveDestPaths(workspacePath: Path, segments: Segments): Paths = {
    val segmentStrings = makeSegmentStrings(segments)
    val targetPath = workspacePath / segmentStrings
    Paths(targetPath, targetPath / 'dest, targetPath / "meta.json", targetPath / 'log)
  }

  // check if the build itself has changed
  def classLoaderSig = Thread.currentThread().getContextClassLoader match {
    case scl: SpecialClassLoader => scl.classpathSignature
    case ucl: URLClassLoader => SpecialClassLoader.initialClasspathSignature(ucl)
    case _ => Nil

  }
  case class Results(rawValues: Seq[Result[Any]],
                     evaluated: Agg[Task[_]],
                     transitive: Agg[Task[_]],
                     failing: MultiBiMap[Either[Task[_], Labelled[_]], Result.Failing[_]],
                     results: collection.Map[Task[_], Result[Any]]){
    def values = rawValues.collect{case Result.Success(v) => v}
  }
}
