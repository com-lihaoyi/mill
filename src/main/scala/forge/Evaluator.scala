package forge


import play.api.libs.json.{JsValue, Json}

import scala.collection.mutable
import ammonite.ops._
class Evaluator(workspacePath: Path,
                labeling: Map[Target[_], Seq[String]]){

  def evaluate(targets: OSet[Target[_]]): Evaluator.Results = {
    mkdir(workspacePath)

    val sortedGroups = Evaluator.groupAroundNamedTargets(
      Evaluator.topoSortedTransitiveTargets(targets),
      labeling
    )

    val evaluated = new MutableOSet[Target[_]]
    val results = mutable.Map.empty[Target[_], Any]
    val groupHashes = mutable.Map.empty[Int, Int]
    for (groupIndex <- sortedGroups.keys()){
      val group = sortedGroups.lookupKey(groupIndex)
      val (inputsHash, newResults, newEvaluated) = evaluateGroupCached(
        group,
        results,
        groupHashes,
        sortedGroups
      )
      evaluated.appendAll(newEvaluated)
      for((k, v) <- newResults) results.put(k, v)
      groupHashes(groupIndex) = inputsHash

    }

    Evaluator.Results(targets.items.map(results), evaluated)
  }

  def evaluateGroupCached(group: OSet[Target[_]],
                          results: collection.Map[Target[_], Any],
                          groupHashes: collection.Map[Int, Int],
                          sortedGroups: MultiBiMap[Int, Target[_]]) = {

    pprint.log(group)
    val (externalInputs, terminals) = partitionGroupInputOutput(group, results)
    val upstreamGroupIds = OSet.from(externalInputs.map(sortedGroups.lookupValue), dedup = true)

    val inputsHash =
      externalInputs.toIterator.map(results).hashCode +
      group.toIterator.map(_.sideHash).hashCode +
      upstreamGroupIds.toIterator.map(groupHashes).hashCode

    val primeLabel = labeling(terminals.items(0))


    val targetDestPath = workspacePath / primeLabel
    val metadataPath = targetDestPath / up / (targetDestPath.last + ".forge.json")

    val cached = for{
      json <- util.Try(Json.parse(read.getInputStream(metadataPath))).toOption
      (hash, terminalResults) <- Json.fromJson[(Int, Seq[JsValue])](json).asOpt
      _ = println("cached hash " + hash)
      if hash == inputsHash
    } yield terminalResults

    cached match{
      case Some(terminalResults) =>
        val newResults = mutable.Map.empty[Target[_], Any]
        for((terminal, res) <- terminals.items.zip(terminalResults)){
          newResults(terminal) = terminal.formatter.reads(res).get
        }
        (inputsHash, newResults, Nil)

      case _ =>
        val (newResults, newEvaluated, terminalResults) = {
          evaluateGroup(group, results, terminals, targetDestPath)
        }


        write.over(
          metadataPath,
          Json.prettyPrint(
            Json.toJson((inputsHash , terminalResults))
          ),
        )

        (inputsHash, newResults, newEvaluated)
    }
  }

  def partitionGroupInputOutput(group: OSet[Target[_]],
                                results: collection.Map[Target[_], Any]) = {
    val allInputs = group.items.flatMap(_.inputs)
    val (internalInputs, externalInputs) = allInputs.partition(group.contains)
    val internalInputSet = internalInputs.toSet
    val terminals = group.filter(!internalInputSet(_))
    (OSet.from(externalInputs, dedup=true), terminals)
  }

  def evaluateGroup(group: OSet[Target[_]],
                    results: collection.Map[Target[_], Any],
                    terminals: OSet[Target[_]],
                    targetDestPath: Path) = {

    rm(targetDestPath)
    val terminalResults = mutable.Buffer.empty[JsValue]
    val newEvaluated = mutable.Buffer.empty[Target[_]]
    val newResults = mutable.Map.empty[Target[_], Any]
    for (target <- group.items) {
      newEvaluated.append(target)
      val targetInputValues = target.inputs.toVector.map(x =>
        newResults.getOrElse(x, results(x))
      )
      if (!labeling.contains(target)) {
        newResults(target) = target.evaluate(new Args(targetInputValues, targetDestPath))
      } else {
        val (res, serialized) = target.evaluateAndWrite(
          new Args(targetInputValues, targetDestPath)
        )
        if (terminals.contains(target)) {
          terminalResults.append(serialized)
        }
        newResults(target) = res
      }
    }

    (newResults, newEvaluated, terminalResults)
  }

}


object Evaluator{
  class TopoSorted private[Evaluator] (val values: OSet[Target[_]])
  case class Results(values: Seq[Any], evaluated: OSet[Target[_]])
  def groupAroundNamedTargets(topoSortedTargets: TopoSorted,
                              labeling: Map[Target[_], Seq[String]]): MultiBiMap[Int, Target[_]] = {

    val grouping = new MutableMultiBiMap[Int, Target[_]]()

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
    val output = new MutableMultiBiMap[Int, Target[_]]
    for(target <- topoSortedTargets.values.items){
      for(targetGroup <- grouping.lookupValueOpt(target)){
        val shifted = grouping.removeAll(targetGroup)
        output.addAll(output.keys().length, shifted.reverse)
      }
    }
    output
  }

  /**
    * Takes the given targets, finds all the targets they transitively depend
    * on, and sort them topologically. Fails if there are dependency cycles
    */
  def topoSortedTransitiveTargets(sourceTargets: OSet[Target[_]]): TopoSorted = {
    val transitiveTargets = new MutableOSet[Target[_]]
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