package mill.eval

import java.net.URLClassLoader

import ammonite.ops._
import ammonite.runtime.SpecialClassLoader
import mill.define.{Segment, Segments, NamedTask, Graph, Target, Task}
import mill.util
import mill.util._
import mill.util.Strict.Agg

import scala.collection.mutable
import scala.util.control.NonFatal
case class Labelled[T](target: NamedTask[T],
                       segments: Segments){
  def format = target match{
    case t: Target[T] => Some(t.readWrite.asInstanceOf[upickle.default.ReadWriter[T]])
    case _ => None
  }
  def writer = target match{
    case t: mill.define.Command[T] => Some(t.writer.asInstanceOf[upickle.default.Writer[T]])
    case t: Target[T] => Some(t.readWrite.asInstanceOf[upickle.default.ReadWriter[T]])
    case _ => None
  }
}
class Evaluator[T](val workspacePath: Path,
                   val basePath: Path,
                   val rootModule: mill.Module,
                   log: Logger,
                   val classLoaderSig: Seq[(Path, Long)] = Evaluator.classLoaderSig){



  val workerCache = mutable.Map.empty[Ctx.Loader[_], Any]
//  workerCache(Discovered.Mapping) = rootModule
  def evaluate(goals: Agg[Task[_]]): Evaluator.Results = {
    mkdir(workspacePath)

    val transitive = Graph.transitiveTargets(goals)
    val topoSorted = Graph.topoSorted(transitive)
    val sortedGroups = Graph.groupAroundImportantTargets(topoSorted){
      case t: NamedTask[Any]   =>
        val segments = t.ctx.segments
        val (finalTaskOverrides, enclosing) = t match{
          case t: Target[_] => rootModule.segmentsToTargets(segments).ctx.overrides -> t.ctx.enclosing
          case c: mill.define.Command[_] => rootModule.segmentsToCommands(segments).overrides -> c.ctx.enclosing
        }
        val additional =
          if (finalTaskOverrides == t.ctx.overrides) Nil
          else Seq(Segment.Label("overriden"), Segment.Label(enclosing))

        Right(Labelled(t, segments ++ additional))
      case t if goals.contains(t) => Left(t)
    }

    val evaluated = new Agg.Mutable[Task[_]]
    val results = mutable.LinkedHashMap.empty[Task[_], Result[Any]]

    for (((terminal, group), i) <- sortedGroups.items().zipWithIndex){
      // Increment the counter message by 1 to go from 1/10 to 10/10 instead of 0/10 to 9/10
      val counterMsg = (i+1) + "/" + sortedGroups.keyCount
      val (newResults, newEvaluated) = evaluateGroupCached(terminal, group, results, counterMsg)
      for(ev <- newEvaluated){
        evaluated.append(ev)
      }
      for((k, v) <- newResults) results.put(k, v)

    }

    val failing = new util.MultiBiMap.Mutable[Either[Task[_], Labelled[_]], Result.Failing]
    for((k, vs) <- sortedGroups.items()){
      failing.addAll(k, vs.items.flatMap(results.get).collect{case f: Result.Failing => f})
    }
    Evaluator.Results(
      goals.indexed.map(results),
      evaluated,
      transitive,
      failing,
      results
    )
  }


  def evaluateGroupCached(terminal: Either[Task[_], Labelled[_]],
                          group: Agg[Task[_]],
                          results: collection.Map[Task[_], Result[Any]],
                          counterMsg: String): (collection.Map[Task[_], Result[Any]], Seq[Task[_]]) = {


    val externalInputs = group.items.flatMap(_.inputs).filter(!group.contains(_))

    val inputsHash =
      externalInputs.map(results).toVector.hashCode +
      group.toIterator.map(_.sideHash).toVector.hashCode() +
      classLoaderSig.hashCode()

    terminal match{
      case Left(task) =>
        evaluateGroup(
          group,
          results,
          groupBasePath = None,
          paths = None,
          maybeTargetLabel = None,
          counterMsg = counterMsg
        )
      case Right(labelledTarget) =>
        val paths = Evaluator.resolveDestPaths(workspacePath, labelledTarget.segments)
        val groupBasePath = basePath / Evaluator.makeSegmentStrings(labelledTarget.segments)
        mkdir(paths.out)
        val cached = for{
          json <- scala.util.Try(upickle.json.read(read(paths.meta))).toOption
          (cachedHash, terminalResult) <- scala.util.Try(upickle.default.readJs[(Int, upickle.Js.Value)](json)).toOption
          if cachedHash == inputsHash
          reader <- labelledTarget.format
          parsed <- reader.read.lift(terminalResult)
        } yield parsed

        cached match{
          case Some(parsed) =>
            val newResults = mutable.LinkedHashMap.empty[Task[_], Result[Any]]
            newResults(labelledTarget.target) = parsed
            (newResults, Nil)

          case _ =>

            val Seq(first, rest @_*) = labelledTarget.segments.value
            val msgParts = Seq(first.asInstanceOf[Segment.Label].value) ++ rest.map{
              case Segment.Label(s) => "." + s
              case Segment.Cross(s) => "[" + s.mkString(",") + "]"
            }

            if (labelledTarget.target.flushDest) rm(paths.dest)
            val (newResults, newEvaluated) = evaluateGroup(
              group,
              results,
              groupBasePath = Some(groupBasePath),
              paths = Some(paths),
              maybeTargetLabel = Some(msgParts.mkString),
              counterMsg = counterMsg
            )

            newResults(labelledTarget.target) match{
              case Result.Success(v) =>
                val terminalResult = labelledTarget
                  .writer
                  .asInstanceOf[Option[upickle.default.Writer[Any]]]
                  .map(_.write(v))

                for(t <- terminalResult){
                  write.over(paths.meta, upickle.default.write(inputsHash -> t, indent = 4))
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
                    results: collection.Map[Task[_], Result[Any]],
                    groupBasePath: Option[Path],
                    paths: Option[Evaluator.Paths],
                    maybeTargetLabel: Option[String],
                    counterMsg: String) = {


    val newEvaluated = mutable.Buffer.empty[Task[_]]
    val newResults = mutable.LinkedHashMap.empty[Task[_], Result[Any]]

    val nonEvaluatedTargets = group.indexed.filterNot(results.contains)

    maybeTargetLabel.foreach { targetLabel =>
      val inputResults = for {
        target <- nonEvaluatedTargets
        item <- target.inputs.filterNot(group.contains)
      } yield results(item)

      val logRun = inputResults.forall(_.isInstanceOf[Result.Success[_]])

      if(logRun) { log.ticker(s"[$counterMsg] $targetLabel ") }
    }

    val multiLogger = resolveLogger(paths.map(_.log))

    for (target <- nonEvaluatedTargets) {

      newEvaluated.append(target)
      val targetInputValues = target.inputs
        .map(x => newResults.getOrElse(x, results(x)))
        .collect{ case Result.Success(v) => v }

      val res =
        if (targetInputValues.length != target.inputs.length) Result.Skipped
        else {
          val args = new Ctx(
            targetInputValues.toArray[Any],
            paths.map(_.dest).orNull,
            groupBasePath.orNull,
            multiLogger,
            new Ctx.LoaderCtx{
              def load[T](x: Ctx.Loader[T]): T = {
                workerCache.getOrElseUpdate(x, x.make()).asInstanceOf[T]
              }
            }
          )

          val out = System.out
          val err = System.err
          try{
            System.setErr(multiLogger.errorStream)
            System.setOut(multiLogger.outputStream)
            Console.withOut(multiLogger.outputStream){
              Console.withErr(multiLogger.errorStream){
                target.evaluate(args)
              }
            }
          }catch{ case NonFatal(e) =>
            val currentStack = new Exception().getStackTrace
            Result.Exception(e, currentStack)
          }finally{
            System.setErr(err)
            System.setOut(out)
          }
        }

      newResults(target) = res
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
                     failing: MultiBiMap[Either[Task[_], Labelled[_]], Result.Failing],
                     results: collection.Map[Task[_], Result[Any]]){
    def values = rawValues.collect{case Result.Success(v) => v}
  }
}
