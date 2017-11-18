package mill.eval

import ammonite.ops._
import mill.define.{Target, Task}
import mill.discover.Mirror.LabelledTarget
import mill.util
import mill.util.{Args, MultiBiMap, OSet}

import scala.collection.mutable
class Evaluator(workspacePath: Path,
                labeling: Map[Target[_], LabelledTarget[_]],
                log: String => Unit){

  def evaluate(goals: OSet[Task[_]]): Evaluator.Results = {
    mkdir(workspacePath)

    val transitive = Evaluator.transitiveTargets(goals)
    val topoSorted = Evaluator.topoSorted(transitive)
    val sortedGroups = Evaluator.groupAroundImportantTargets(topoSorted){
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
    for((k, vs) <- sortedGroups.items){
      failing.addAll(k, vs.items.flatMap(results.get).collect{case f: Result.Failing => f})
    }
    Evaluator.Results(goals.indexed.map(results), evaluated, transitive, failing)
  }

  def resolveDestPaths(t: LabelledTarget[_]): (Path, Path) = {
    val targetDestPath = workspacePath / t.segments
    val metadataPath = targetDestPath / up / (targetDestPath.last + ".mill.json")
    (targetDestPath, metadataPath)
  }

  def evaluateGroupCached(terminal: Either[Task[_], LabelledTarget[_]],
                          group: OSet[Task[_]],
                          results: collection.Map[Task[_], Result[Any]]): (collection.Map[Task[_], Result[Any]], Seq[Task[_]]) = {


    val externalInputs = group.items.flatMap(_.inputs).filter(!group.contains(_))

    val inputsHash =
      externalInputs.map(results).toVector.hashCode +
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
            val newResults = mutable.LinkedHashMap.empty[Task[_], Result[Any]]
            newResults(labelledTarget.target) = labelledTarget.format.read(terminalResult)
            (newResults, Nil)

          case _ =>

            log("Running " + labelledTarget.segments.mkString("."))
            if (labelledTarget.target.flushDest) rm(destPath)
            val (newResults, newEvaluated) = evaluateGroup(group, results, Some(destPath))

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
                    targetDestPath: Option[Path]) = {


    val newEvaluated = mutable.Buffer.empty[Task[_]]
    val newResults = mutable.LinkedHashMap.empty[Task[_], Result[Any]]
    for (target <- group.items if !results.contains(target)) {

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
  class TopoSorted private[Evaluator](val values: OSet[Task[_]])
  case class Results(rawValues: Seq[Result[Any]],
                     evaluated: OSet[Task[_]],
                     transitive: OSet[Task[_]],
                     failing: MultiBiMap[Either[Task[_], LabelledTarget[_]], Result.Failing]){
    def values = rawValues.collect{case Result.Success(v) => v}
  }
  def groupAroundImportantTargets[T](topoSortedTargets: TopoSorted)
                                    (important: PartialFunction[Task[_], T]): MultiBiMap[T, Task[_]] = {

    val output = new MultiBiMap.Mutable[T, Task[_]]()
    for ((target, t) <- topoSortedTargets.values.flatMap(t => important.lift(t).map((t, _)))) {

      val transitiveTargets = new OSet.Mutable[Task[_]]
      def rec(t: Task[_]): Unit = {
        if (transitiveTargets.contains(t)) () // do nothing
        else if (important.isDefinedAt(t) && t != target) () // do nothing
        else {
          transitiveTargets.append(t)
          t.inputs.foreach(rec)
        }
      }
      rec(target)
      output.addAll(t, topoSorted(transitiveTargets).values)
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

    val indexed = transitiveTargets.indexed
    val targetIndices = indexed.zipWithIndex.toMap

    val numberedEdges =
      for(t <- transitiveTargets.items)
      yield t.inputs.collect(targetIndices)

    val sortedClusters = Tarjans(numberedEdges)
    val nonTrivialClusters = sortedClusters.filter(_.length > 1)
    assert(nonTrivialClusters.isEmpty, nonTrivialClusters)
    new TopoSorted(OSet.from(sortedClusters.flatten.map(indexed)))
  }
}