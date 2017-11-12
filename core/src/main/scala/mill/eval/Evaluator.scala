package mill.eval

import ammonite.ops._
import mill.define.Task
import mill.discover.Labelled
import mill.util.{Args, MultiBiMap, OSet}
import play.api.libs.json.{Format, JsValue, Json}

import scala.collection.mutable
class Evaluator(workspacePath: Path,
                labeling: Map[Task[_], Labelled[_]]){

  def evaluate(targets: OSet[Task[_]]): Evaluator.Results = {
    mkdir(workspacePath)

    val transitive = Evaluator.transitiveTargets(targets)
    val topoSorted = Evaluator.topoSorted(transitive)
    val sortedGroups = Evaluator.groupAroundNamedTargets(topoSorted, labeling)

    val evaluated = new OSet.Mutable[Task[_]]
    val results = mutable.LinkedHashMap.empty[Task[_], Any]

    for (groupIndex <- sortedGroups.keys()){
      val group = sortedGroups.lookupKey(groupIndex)

      val (newResults, newEvaluated) = evaluateGroupCached(
        group,
        results,
        sortedGroups
      )
      evaluated.appendAll(newEvaluated)
      for((k, v) <- newResults) results.put(k, v)

    }

    Evaluator.Results(targets.items.map(results), evaluated, transitive)
  }

  def evaluateGroupCached(group: OSet[Task[_]],
                          results: collection.Map[Task[_], Any],
                          sortedGroups: MultiBiMap[Int, Task[_]]): (collection.Map[Task[_], Any], Seq[Task[_]]) = {


    val (externalInputs, terminals) = partitionGroupInputOutput(group, results)

    val inputsHash =
      externalInputs.toIterator.map(results).toVector.hashCode +
      group.toIterator.map(_.sideHash).toVector.hashCode()

    val (targetDestPath, metadataPath) = labeling.get(terminals.items(0)) match{
      case Some(labeling) =>
        val targetDestPath = workspacePath / labeling.segments
        val metadataPath = targetDestPath / up / (targetDestPath.last + ".mill.json")
        (Some(targetDestPath), Some(metadataPath))
      case None => (None, None)
    }

    val cached = for{
      metadataPath <- metadataPath
      json <- scala.util.Try(Json.parse(read.getInputStream(metadataPath))).toOption
      (cachedHash, terminalResults) <- Json.fromJson[(Int, Seq[JsValue])](json).asOpt
      if cachedHash == inputsHash
    } yield terminalResults

    cached match{
      case Some(terminalResults) =>
        val newResults = mutable.LinkedHashMap.empty[Task[_], Any]
        for((terminal, res) <- terminals.items.zip(terminalResults)){
          newResults(terminal) = labeling(terminal).format.reads(res).get
        }
        (newResults, Nil)

      case _ =>

        val labeled = group.collect(labeling)
        if (labeled.nonEmpty){
          println(fansi.Color.Blue("Running " + labeled.map(_.segments.mkString(".")).mkString(", ")))
        }
        val (newResults, newEvaluated, terminalResults) = evaluateGroup(group, results, targetDestPath)

        metadataPath.foreach(
          write.over(
            _,
            Json.prettyPrint(
              Json.toJson(inputsHash -> terminals.toList.map(terminalResults))
            ),
          )
        )

        (newResults, newEvaluated)
    }
  }

  def partitionGroupInputOutput(group: OSet[Task[_]],
                                results: collection.Map[Task[_], Any]) = {
    val allInputs = group.items.flatMap(_.inputs)
    val (internalInputs, externalInputs) = allInputs.partition(group.contains)
    val internalInputSet = internalInputs.toSet
    val terminals = group.filter(x => !internalInputSet(x) || labeling.contains(x))
    (OSet.from(externalInputs.distinct), terminals)
  }

  def evaluateGroup(group: OSet[Task[_]],
                    results: collection.Map[Task[_], Any],
                    targetDestPath: Option[Path]) = {

    targetDestPath.foreach(rm)
    val terminalResults = mutable.LinkedHashMap.empty[Task[_], JsValue]
    val newEvaluated = mutable.Buffer.empty[Task[_]]
    val newResults = mutable.LinkedHashMap.empty[Task[_], Any]
    for (target <- group.items) {
      newEvaluated.append(target)
      val targetInputValues = target.inputs.toVector.map(x =>
        newResults.getOrElse(x, results(x))
      )

      val args = new Args(targetInputValues, targetDestPath.orNull)
      val res = target.evaluate(args)
      for(targetLabel <- labeling.get(target)){
        terminalResults(target) = targetLabel
          .format
          .asInstanceOf[Format[Any]]
          .writes(res.asInstanceOf[Any])
      }
      newResults(target) = res
    }

    (newResults, newEvaluated, terminalResults)
  }

}


object Evaluator{
  class TopoSorted private[Evaluator](val values: OSet[Task[_]])
  case class Results(values: Seq[Any], evaluated: OSet[Task[_]], transitive: OSet[Task[_]])
  def groupAroundNamedTargets(topoSortedTargets: TopoSorted,
                              labeling: Map[Task[_], Labelled[_]]): MultiBiMap[Int, Task[_]] = {

    val grouping = new MultiBiMap.Mutable[Int, Task[_]]()

    var groupCount = 0

    for(target <- topoSortedTargets.values.items.reverseIterator){
      if (!grouping.containsValue(target)){
        grouping.add(groupCount, target)
        groupCount += 1
      }

      val targetGroup = grouping.lookupValue(target)
      for(upstream <- target.inputs){
        grouping.lookupValueOpt(upstream) match{
          case None if !labeling.contains(upstream) =>
            grouping.add(targetGroup, upstream)
          case Some(upstreamGroup)
            if !labeling.contains(upstream) && upstreamGroup != targetGroup =>
            val upstreamTargets = grouping.removeAll(upstreamGroup)
            grouping.addAll(targetGroup, upstreamTargets)
          case _ => //donothing
        }
      }
    }


    // Sort groups amongst themselves, and sort the contents of each group
    // before aggregating it into the final output
    val groupGraph = mutable.Buffer.fill[Seq[Int]](groupCount)(Nil)
    for((groupId, groupTasks) <- grouping.items()){
      groupGraph(groupId) =
        groupTasks.toIterator.flatMap(_.inputs).map(grouping.lookupValue).toArray.distinct.toSeq
    }
    // Given input topoSortedTargets has no cycles, group graph should not have cycles
    val groupOrdering = Tarjans.apply(groupGraph)


    val targetOrdering = topoSortedTargets.values.items.zipWithIndex.toMap
    val output = new MultiBiMap.Mutable[Int, Task[_]]
    for((groupIndices, i) <- groupOrdering.zipWithIndex){
      val sortedGroup = OSet.from(
        groupIndices.flatMap(grouping.lookupKeyOpt).flatten.sortBy(targetOrdering)
      )
      output.addAll(i, sortedGroup)
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
      yield t.inputs.map(targetIndices)

    val sortedClusters = Tarjans(numberedEdges)
    val nonTrivialClusters = sortedClusters.filter(_.length > 1)
    assert(nonTrivialClusters.isEmpty, nonTrivialClusters)
    new TopoSorted(OSet.from(sortedClusters.flatten.map(transitiveTargets.items)))
  }
}