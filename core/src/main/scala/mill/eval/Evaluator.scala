package mill.eval

import ammonite.ops._
import ammonite.runtime.SpecialClassLoader
import mill.define.{Graph, Target, Task}
import mill.discover.Mirror
import mill.discover.Mirror.LabelledTarget
import mill.util
import mill.util.{Args, MultiBiMap, OSet}
import scala.collection.mutable

class Evaluator(workspacePath: Path,
                labeling: Map[Target[_], LabelledTarget[_]],
                log: String => Unit){

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
      val (newResults, newEvaluated) = evaluateGroupCached(
        terminal,
        group,
        results
      )
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

    // check if the build itself has changed
    val classLoaderSig = Thread.currentThread().getContextClassLoader match {
      case scl: SpecialClassLoader => scl.classpathSignature
      case _ => Nil
    }

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
            }



            (newResults, newEvaluated)
        }
    }
  }


  def evaluateGroup(group: OSet[Task[_]],
                    results: collection.Map[Task[_], Result[Any]],
                    targetDestPath: Option[Path],
                    maybeTargetLabel: Option[String]
                   ) = {


    val newEvaluated = mutable.Buffer.empty[Task[_]]
    val newResults = mutable.LinkedHashMap.empty[Task[_], Result[Any]]

    val nonEvaluatedTargets = group.indexed.filterNot(results.contains)

    maybeTargetLabel.foreach { targetLabel =>
      val inputResults = for {
        target <- nonEvaluatedTargets
        item <- target.inputs.filterNot(group.contains)
      } yield results(item)

      val logRun = inputResults.forall(_.isInstanceOf[Result.Success[_]])

      if(logRun) { log("Running " + targetLabel) }
    }

    for (target <- nonEvaluatedTargets) {

      newEvaluated.append(target)
      val targetInputValues = target.inputs
        .map(x => newResults.getOrElse(x, results(x)))
        .collect{ case Result.Success(v) => v }

      val res =
        if (targetInputValues.length != target.inputs.length) Result.Skipped
        else {
          val args = new Args(targetInputValues.toArray[Any], targetDestPath.orNull)
          target.evaluate(args)
        }

      newResults(target) = res
    }

    (newResults, newEvaluated)
  }

}


object Evaluator{

  case class Results(rawValues: Seq[Result[Any]],
                     evaluated: OSet[Task[_]],
                     transitive: OSet[Task[_]],
                     failing: MultiBiMap[Either[Task[_], LabelledTarget[_]], Result.Failing]){
    def values = rawValues.collect{case Result.Success(v) => v}
  }
}
