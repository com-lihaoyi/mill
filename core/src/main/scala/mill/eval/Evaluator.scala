package mill.eval

import java.net.URLClassLoader

import ammonite.ops._
import ammonite.runtime.SpecialClassLoader
import mill.define.{Graph, Target, Task, Worker}
import mill.discover.Mirror
import mill.discover.Mirror.LabelledTarget
import mill.discover.Mirror.Segment.{Cross, Label}
import mill.util
import mill.util._

import scala.collection.mutable

class Evaluator(workspacePath: Path,
                labeling: Map[Target[_], LabelledTarget[_]],
                log: Logger,
                sel: List[Mirror.Segment] = List(),
                classLoaderSig: Seq[(Path, Long)] = Evaluator.classLoaderSig){
  val workerCache = mutable.Map.empty[Ctx.Loader[_], Any]
  def evaluate(goals: OSet[Task[_]]): Evaluator.Results = {
    mkdir(workspacePath)

    val transitive = Graph.transitiveTargets(goals)
    val topoSorted = Graph.topoSorted(transitive)
    val sortedGroups = Graph.groupAroundImportantTargets(topoSorted){
      case t: Target[_] if labeling.contains(t) || goals.contains(t) => Right(labeling(t))
      case t if goals.contains(t) => Left(t)
    }

    val evaluated = new OSet.Mutable[Task[_]]
    val results = mutable.LinkedHashMap.empty[Task[_], Result[Any]]

    for ((terminal, group)<- sortedGroups.items()){
      val (newResults, newEvaluated) = evaluateGroupCached(terminal, group, results)
      for(ev <- newEvaluated){
        evaluated.append(ev)
      }
      for((k, v) <- newResults) results.put(k, v)

    }

    val failing = new util.MultiBiMap.Mutable[Either[Task[_], LabelledTarget[_]], Result.Failing]
    for((k, vs) <- sortedGroups.items()){
      failing.addAll(k, vs.items.flatMap(results.get).collect{case f: Result.Failing => f})
    }
    Evaluator.Results(goals.indexed.map(results), evaluated, transitive, failing)
  }

  def resolveDestPaths(t: LabelledTarget[_]): (Path, Path) = {
    val segmentStrings = t.segments.flatMap{
      case Mirror.Segment.Label(s) => Seq(s)
      case Mirror.Segment.Cross(values) => values.map(_.toString)
    }
    val targetDestPath = workspacePath / segmentStrings
    val metadataPath = targetDestPath / up / (targetDestPath.last + ".mill.json")
    (targetDestPath, metadataPath)
  }

  def evaluateGroupCached(terminal: Either[Task[_], LabelledTarget[_]],
                          group: OSet[Task[_]],
                          results: collection.Map[Task[_], Result[Any]]): (collection.Map[Task[_], Result[Any]], Seq[Task[_]]) = {


    val externalInputs = group.items.flatMap(_.inputs).filter(!group.contains(_))

    val inputsHash =
      externalInputs.map(results).toVector.hashCode +
      group.toIterator.map(_.sideHash).toVector.hashCode() +
      classLoaderSig.hashCode()

    terminal match{
      case Left(task) =>
        evaluateGroup(group, results, targetDestPath = None, maybeTargetLabel = None)
      case Right(labelledTarget) =>
        val (destPath, metadataPath) = resolveDestPaths(labelledTarget)
        val cached = for{
          json <- scala.util.Try(upickle.json.read(read(metadataPath))).toOption
          (cachedHash, terminalResult) <- scala.util.Try(upickle.default.readJs[(Int, upickle.Js.Value)](json)).toOption
          if cachedHash == inputsHash
        } yield terminalResult

        cached match{
          case Some(terminalResult) =>
            val newResults = mutable.LinkedHashMap.empty[Task[_], Result[Any]]
            newResults(labelledTarget.target) = labelledTarget.format.read(terminalResult)
            (newResults, Nil)

          case _ =>

            val Seq(first, rest @_*) = labelledTarget.segments
            val msgParts = Seq(first.asInstanceOf[Mirror.Segment.Label].value) ++ rest.map{
              case Mirror.Segment.Label(s) => "." + s
              case Mirror.Segment.Cross(s) => "[" + s.mkString(",") + "]"
            }

            if (labelledTarget.target.flushDest) rm(destPath)
            val (newResults, newEvaluated) = evaluateGroup(
              group,
              results,
              Some(destPath),
              maybeTargetLabel = Some(msgParts.mkString))

            newResults(labelledTarget.target) match{
              case Result.Success(v) =>
                val terminalResult = labelledTarget
                  .format
                  .asInstanceOf[upickle.default.ReadWriter[Any]]
                  .write(v)

                write.over(metadataPath, upickle.default.write(inputsHash -> terminalResult, indent = 4))
              case _ =>
                // Wipe out any cached metadata.mill.json file that exists, so
                // a following run won't look at the cached metadata file and
                // assume it's associated with the possibly-borked state of the
                // destPath after an evaluation failure.
                rm(metadataPath)
            }



            (newResults, newEvaluated)
        }
    }
  }


  def evaluateGroup(group: OSet[Task[_]],
                    results: collection.Map[Task[_], Result[Any]],
                    targetDestPath: Option[Path],
                    maybeTargetLabel: Option[String]) = {


    val newEvaluated = mutable.Buffer.empty[Task[_]]
    val newResults = mutable.LinkedHashMap.empty[Task[_], Result[Any]]

    val nonEvaluatedTargets = group.indexed.filterNot(results.contains)

    maybeTargetLabel.foreach { targetLabel =>
      val inputResults = for {
        target <- nonEvaluatedTargets
        item <- target.inputs.filterNot(group.contains)
      } yield results(item)

      val logRun = inputResults.forall(_.isInstanceOf[Result.Success[_]])

      if(logRun) { log.info("Running " + targetLabel) }
    }

    val multiLogger = resolveLogger(targetDestPath)

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
            targetDestPath.orNull,
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
            System.setErr(multiLogger.outputStream)
            System.setOut(multiLogger.outputStream)
            Console.withOut(multiLogger.outputStream){
              Console.withErr(multiLogger.outputStream){
                target.evaluate(args)
              }
            }
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

  def resolveLogger(targetDestPath: Option[Path]): Logger = {
    if (targetDestPath.isEmpty && sel.isEmpty)
      log
    else {
      val path = targetDestPath.getOrElse(
        sel.foldLeft[Path](pwd / 'out) {
          case (d, Label(s)) => d / s
          case (d, Cross(args)) => d / args.map(_.toString)
        }
      )
      val dir = path / up
      mkdir(dir)
      val file = dir / (path.last + ".log")
      rm(file)
      MultiLogger(log, FileLogger(file))
    }
  }

}


object Evaluator{
  // check if the build itself has changed
  def classLoaderSig = Thread.currentThread().getContextClassLoader match {
    case scl: SpecialClassLoader => scl.classpathSignature
    case ucl: URLClassLoader => SpecialClassLoader.initialClasspathSignature(ucl)
    case _ => Nil

  }
  case class Results(rawValues: Seq[Result[Any]],
                     evaluated: OSet[Task[_]],
                     transitive: OSet[Task[_]],
                     failing: MultiBiMap[Either[Task[_], LabelledTarget[_]], Result.Failing]){
    def values = rawValues.collect{case Result.Success(v) => v}
  }
}
