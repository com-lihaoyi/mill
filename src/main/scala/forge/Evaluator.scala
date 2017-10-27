package forge

import java.nio.{file => jnio}

import play.api.libs.json.Json
import sourcecode.Enclosing

import scala.collection.mutable

class Evaluator(workspacePath: jnio.Path,
                enclosingBase: DefCtx){

  val resultCache = mutable.Map.empty[String, (Int, String)]
  def evaluate(targets: Seq[Target[_]]): Evaluator.Results = {
    jnio.Files.createDirectories(workspacePath)

    val sortedTargets = Evaluator.topoSortedTransitiveTargets(targets)
    pprint.log(sortedTargets.values)
    val evaluated = mutable.Buffer.empty[Target[_]]
    val results = mutable.Map.empty[Target[_], Any]
    for (target <- sortedTargets.values){
      val inputResults = target.inputs.map(results).toIndexedSeq

      val enclosingStr = target.defCtx.label
      val targetDestPath = workspacePath.resolve(
        jnio.Paths.get(enclosingStr.stripSuffix(enclosingBase.label))
      )
      deleteRec(targetDestPath)

      val inputsHash = inputResults.hashCode
      (target.dirty, resultCache.get(target.defCtx.label)) match{
        case (Some(dirtyCheck), Some((hash, res)))
          if hash == inputsHash && !dirtyCheck() =>
          results(target) = target.formatter.reads(Json.parse(res)).get

        case _ =>
          evaluated.append(target)
          if (target.defCtx.anonId.isDefined && target.dirty.isEmpty) {
            val res = target.evaluate(new Args(inputResults, targetDestPath))
            results(target) = res
          }else{
            val (res, serialized) = target.evaluateAndWrite(new Args(inputResults, targetDestPath))
            resultCache(target.defCtx.label) = (inputsHash, serialized)
            results(target) = res
          }

      }

    }
    Evaluator.Results(targets.map(results), evaluated)
  }
  def deleteRec(path: jnio.Path) = {
    if (jnio.Files.exists(path)){
      import collection.JavaConverters._
      jnio.Files.walk(path).iterator()
        .asScala
        .toArray
        .reverseIterator
        .map(jnio.Files.deleteIfExists)
    }
  }
}


object Evaluator{
  class TopoSorted private[Evaluator] (val values: Seq[Target[_]])
  case class Results(values: Seq[Any], evaluated: Seq[Target[_]])
  def groupAroundNamedTargets(topoSortedTargets: TopoSorted): Seq[Seq[Target[_]]] = {
    val grouping = new MultiBiMap[Int, Target[_]]()

    var groupCount = 0

    for(target <- topoSortedTargets.values.reverseIterator){

      if (!grouping.containsValue(target)){
        grouping.add(groupCount, target)
        groupCount += 1
      }

      val targetGroup = grouping.lookupValue(target)
      for(upstream <- target.inputs){
        grouping.lookupValueOpt(upstream) match{
          case None if upstream.dirty.isEmpty && upstream.defCtx.anonId.nonEmpty =>
            grouping.add(targetGroup, upstream)
          case Some(upstreamGroup) if upstreamGroup == targetGroup =>
            val upstreamTargets = grouping.removeAll(upstreamGroup)

            grouping.addAll(targetGroup, upstreamTargets)
          case _ => //donothing
        }
      }
    }
    val output = mutable.Buffer.empty[Seq[Target[_]]]
    for(target <- topoSortedTargets.values.reverseIterator){
      for(targetGroup <- grouping.lookupValueOpt(target)){
        output.append(grouping.removeAll(targetGroup))
      }
    }
    output.map(_.sortBy(topoSortedTargets.values.indexOf)).reverse
  }

  /**
    * Takes the given targets, finds
    */
  def topoSortedTransitiveTargets(sourceTargets: Seq[Target[_]]): TopoSorted = {
    val transitiveTargetSet = mutable.Set.empty[Target[_]]
    val transitiveTargets = mutable.Buffer.empty[Target[_]]
    def rec(t: Target[_]): Unit = {
      if (transitiveTargetSet.contains(t)) () // do nothing
      else {
        transitiveTargetSet.add(t)
        transitiveTargets.append(t)
        t.inputs.foreach(rec)
      }
    }

    sourceTargets.foreach(rec)
    val targetIndices = transitiveTargets.zipWithIndex.toMap

    val numberedEdges =
      for(i <- transitiveTargets.indices)
        yield transitiveTargets(i).inputs.map(targetIndices)

    val sortedClusters = Tarjans(numberedEdges)
    val nonTrivialClusters = sortedClusters.filter(_.length > 1)
    assert(nonTrivialClusters.isEmpty, nonTrivialClusters)
    new TopoSorted(sortedClusters.flatten.map(transitiveTargets))
  }
}