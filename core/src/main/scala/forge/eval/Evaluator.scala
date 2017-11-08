package forge.eval

import ammonite.ops._
import forge.define.Target
import forge.discover.Labelled
import forge.util.{Args, MultiBiMap, OSet}
import play.api.libs.json.{Format, JsValue, Json}

import scala.collection.mutable
class Evaluator(workspacePath: Path,
                labeling: Map[Target[_], Labelled[_]]){

  def evaluate(targets: OSet[Target[_]]): Evaluator.Results = {
    mkdir(workspacePath)

    val sortedGroups = Evaluator.groupAroundNamedTargets(
      Evaluator.topoSortedTransitiveTargets(targets),
      labeling
    )

    val evaluated = new OSet.Mutable[Target[_]]
    val results = mutable.LinkedHashMap.empty[Target[_], Any]

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

    Evaluator.Results(targets.items.map(results), evaluated)
  }

  def evaluateGroupCached(group: OSet[Target[_]],
                          results: collection.Map[Target[_], Any],
                          sortedGroups: MultiBiMap[Int, Target[_]]): (collection.Map[Target[_], Any], Seq[Target[_]]) = {


    val (externalInputs, terminals) = partitionGroupInputOutput(group, results)

    val inputsHash =
      externalInputs.toIterator.map(results).toVector.hashCode +
      group.toIterator.map(_.sideHash).toVector.hashCode()

    val (targetDestPath, metadataPath) = labeling.get(terminals.items(0)) match{
      case Some(labeling) =>
        val targetDestPath = workspacePath / labeling.segments
        val metadataPath = targetDestPath / up / (targetDestPath.last + ".forge.json")
        (targetDestPath, Some(metadataPath))
      case None => (null, None)
    }

    val cached = for{
      metadataPath <- metadataPath
      json <- scala.util.Try(Json.parse(read.getInputStream(metadataPath))).toOption
      (cachedHash, terminalResults) <- Json.fromJson[(Int, Seq[JsValue])](json).asOpt
      if cachedHash == inputsHash
    } yield terminalResults

    cached match{
      case Some(terminalResults) =>
        val newResults = mutable.LinkedHashMap.empty[Target[_], Any]
        for((terminal, res) <- terminals.items.zip(terminalResults)){
          newResults(terminal) = labeling(terminal).format.reads(res).get
        }
        (newResults, Nil)

      case _ =>
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

  def partitionGroupInputOutput(group: OSet[Target[_]],
                                results: collection.Map[Target[_], Any]) = {
    val allInputs = group.items.flatMap(_.inputs)
    val (internalInputs, externalInputs) = allInputs.partition(group.contains)
    val internalInputSet = internalInputs.toSet
    val terminals = group.filter(!internalInputSet(_))
    (OSet.from(externalInputs.distinct), terminals)
  }

  def evaluateGroup(group: OSet[Target[_]],
                    results: collection.Map[Target[_], Any],
                    targetDestPath: Path) = {

    rm(targetDestPath)
    val terminalResults = mutable.LinkedHashMap.empty[Target[_], JsValue]
    val newEvaluated = mutable.Buffer.empty[Target[_]]
    val newResults = mutable.LinkedHashMap.empty[Target[_], Any]
    for (target <- group.items) {
      newEvaluated.append(target)
      val targetInputValues = target.inputs.toVector.map(x =>
        newResults.getOrElse(x, results(x))
      )

      val args = new Args(targetInputValues, targetDestPath)

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
  class TopoSorted private[Evaluator] (val values: OSet[Target[_]])
  case class Results(values: Seq[Any], evaluated: OSet[Target[_]])
  def groupAroundNamedTargets(topoSortedTargets: TopoSorted,
                              labeling: Map[Target[_], Labelled[_]]): MultiBiMap[Int, Target[_]] = {

    val grouping = new MultiBiMap.Mutable[Int, Target[_]]()

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
          case Some(upstreamGroup) if upstreamGroup == targetGroup =>
            val upstreamTargets = grouping.removeAll(upstreamGroup)

            grouping.addAll(targetGroup, upstreamTargets)
          case _ => //donothing
        }
      }
    }

    val targetOrdering = topoSortedTargets.values.items.zipWithIndex.toMap
    val output = new MultiBiMap.Mutable[Int, Target[_]]

    // Sort groups amongst themselves, and sort the contents of each group
    // before aggregating it into the final output
    for(g <- grouping.values().toArray.sortBy(g => targetOrdering(g.items(0)))){
      output.addAll(output.keys.length, g.toArray.sortBy(targetOrdering))
    }
    output
  }

  /**
    * Takes the given targets, finds all the targets they transitively depend
    * on, and sort them topologically. Fails if there are dependency cycles
    */
  def topoSortedTransitiveTargets(sourceTargets: OSet[Target[_]]): TopoSorted = {
    val transitiveTargets = new OSet.Mutable[Target[_]]
    def rec(t: Target[_]): Unit = {
      if (transitiveTargets.contains(t)) () // do nothing
      else {
        transitiveTargets.append(t)
        t.inputs.foreach(rec)
      }
    }

    sourceTargets.items.foreach(rec)
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