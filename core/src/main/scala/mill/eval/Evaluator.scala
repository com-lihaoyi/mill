package mill.eval

import ammonite.ops._
import mill.define.Task
import mill.discover.Mirror.LabelledTarget
import mill.util.{Args, MultiBiMap, OSet}

import scala.collection.mutable
class Evaluator(workspacePath: Path,
                labeling: Map[Task[_], LabelledTarget[_]]){

  def evaluate(goals: OSet[Task[_]]): Evaluator.Results = {
    mkdir(workspacePath)

    val transitive = Evaluator.transitiveTargets(goals)
    val topoSorted = Evaluator.topoSorted(transitive)
    val sortedGroups = Evaluator.groupAroundImportantTargets(
      topoSorted,
      t => labeling.contains(t) || goals.contains(t)
    )

    val evaluated = new OSet.Mutable[Task[_]]
    val results = mutable.LinkedHashMap.empty[Task[_], Any]

    for ((terminal, group)<- sortedGroups.items()){
      val (newResults, newEvaluated) = evaluateGroupCached(
        labeling.get(terminal) match{
          case Some(labeled) => Right(labeled)
          case None => Left(terminal)
        },
        group,
        results
      )
      for(ev <- newEvaluated){
        evaluated.append(ev)
      }
      for((k, v) <- newResults) results.put(k, v)

    }

    Evaluator.Results(goals.items.map(results), evaluated, transitive)
  }

  def resolveDestPaths(t: LabelledTarget[_]): (Path, Path) = {
    val targetDestPath = workspacePath / t.segments
    val metadataPath = targetDestPath / up / (targetDestPath.last + ".mill.json")
    (targetDestPath, metadataPath)
  }

  def evaluateGroupCached(terminal: Either[Task[_], LabelledTarget[_]],
                          group: OSet[Task[_]],
                          results: collection.Map[Task[_], Any]): (collection.Map[Task[_], Any], Seq[Task[_]]) = {


    val externalInputs = group.items.flatMap(_.inputs).filter(!group.contains(_))

    val inputsHash =
      externalInputs.toIterator.map(results).toVector.hashCode +
      group.toIterator.map(_.sideHash).toVector.hashCode()

    terminal match{
      case Left(task) => evaluateGroup(group, results, None)
      case Right(labelledTarget) =>
        val (destPath, metadataPath) = resolveDestPaths(labelledTarget)
        val cached = for{
          json <- scala.util.Try(upickle.json.read(read(metadataPath))).toOption
          (cachedHash, terminalResult) <- scala.util.Try(upickle.default.readJs[(Int, upickle.Js.Value)](json)).toOption
          if cachedHash == inputsHash
        } yield terminalResult

        cached match{
          case Some(terminalResult) =>
            val newResults = mutable.LinkedHashMap.empty[Task[_], Any]
            newResults(labelledTarget.target) = labelledTarget.format.read(terminalResult)
            (newResults, Nil)

          case _ =>

            println(fansi.Color.Blue("Running " + labelledTarget.segments.mkString(".")))
            if (labelledTarget.target.flushDest) rm(destPath)
            val (newResults, newEvaluated) = evaluateGroup(group, results, Some(destPath))

            val terminalResult = labelledTarget
                .format
                .asInstanceOf[upickle.default.ReadWriter[Any]]
                .write(newResults(labelledTarget.target))

            write.over(metadataPath, upickle.default.write(inputsHash -> terminalResult, indent = 4))
            (newResults, newEvaluated)
        }
    }
  }


  def evaluateGroup(group: OSet[Task[_]],
                    results: collection.Map[Task[_], Any],
                    targetDestPath: Option[Path]) = {


    val newEvaluated = mutable.Buffer.empty[Task[_]]
    val newResults = mutable.LinkedHashMap.empty[Task[_], Any]
    for (target <- group.items if !results.contains(target)) {

      newEvaluated.append(target)
      val targetInputValues = target.inputs.toVector.map(x =>
        newResults.getOrElse(x, results(x))
      )

      val args = new Args(targetInputValues, targetDestPath.orNull)
      val res = target.evaluate(args)

      newResults(target) = res
    }

    (newResults, newEvaluated)
  }

}


object Evaluator{
  class TopoSorted private[Evaluator](val values: OSet[Task[_]])
  case class Results(values: Seq[Any], evaluated: OSet[Task[_]], transitive: OSet[Task[_]])
  def groupAroundImportantTargets(topoSortedTargets: TopoSorted,
                                  important: Task[_] => Boolean): MultiBiMap[Task[_], Task[_]] = {

    val output = new MultiBiMap.Mutable[Task[_], Task[_]]()
    for (target <- topoSortedTargets.values if important(target)) {

      val transitiveTargets = new OSet.Mutable[Task[_]]
      def rec(t: Task[_]): Unit = {
        if (transitiveTargets.contains(t)) () // do nothing
        else if (important(t) && t != target) () // do nothing
        else {
          transitiveTargets.append(t)
          t.inputs.foreach(rec)
        }
      }
      rec(target)
      output.addAll(target, topoSorted(transitiveTargets).values)
    }
    output
  }

  def transitiveTargets(sourceTargets: OSet[Task[_]]): OSet[Task[_]] = {
    val transitiveTargets = new OSet.Mutable[Task[_]]
    def rec(t: Task[_]): Unit = {
      if (transitiveTargets.contains(t)) () // do nothing
      else {
        transitiveTargets.append(t)
        t.inputs.foreach(rec)
      }
    }

    sourceTargets.items.foreach(rec)
    transitiveTargets
  }
  /**
    * Takes the given targets, finds all the targets they transitively depend
    * on, and sort them topologically. Fails if there are dependency cycles
    */
  def topoSorted(transitiveTargets: OSet[Task[_]]): TopoSorted = {

    val targetIndices = transitiveTargets.items.zipWithIndex.toMap

    val numberedEdges =
      for(t <- transitiveTargets.items)
      yield t.inputs.collect(targetIndices)

    val sortedClusters = Tarjans(numberedEdges)
    val nonTrivialClusters = sortedClusters.filter(_.length > 1)
    assert(nonTrivialClusters.isEmpty, nonTrivialClusters)
    new TopoSorted(OSet.from(sortedClusters.flatten.map(transitiveTargets.items)))
  }
}