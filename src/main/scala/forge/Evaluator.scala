package forge

import java.nio.{file => jnio}

import sourcecode.Enclosing

import scala.collection.mutable

class Evaluator(workspacePath: jnio.Path,
                enclosingBase: DefCtx){

  /**
    * Takes the given targets, finds
    */
  def prepareTransitiveTargets(targets: Seq[Target[_]]) = {

    val targetIndices = targets.zipWithIndex.toMap

    val transitiveTargetSet = mutable.Set.empty[Target[_]]
    def rec(t: Target[_]): Unit = {
      if (transitiveTargetSet.contains(t)) () // do nothing
      else {
        transitiveTargetSet.add(t)
        t.inputs.foreach(rec)
      }
    }

    targets.foreach(rec)
    val transitiveTargets = transitiveTargetSet.toVector
    val numberedEdges =
      for(i <- transitiveTargets.indices)
      yield targets(i).inputs.map(targetIndices)

    val sortedClusters = Tarjans(numberedEdges)
    val nonTrivialClusters = sortedClusters.filter(_.length > 1)
    assert(nonTrivialClusters.isEmpty, nonTrivialClusters)
    (transitiveTargets, sortedClusters.flatten)
  }

  def apply[T](t: Target[T])
              (implicit enclosing: Enclosing): T = {
    jnio.Files.createDirectories(workspacePath)

    val (transitiveTargets, sortedTargetIndices) = prepareTransitiveTargets(Seq(t))
    val results = mutable.Map.empty[Target[_], Any]
    for (index <- sortedTargetIndices){

      val target = transitiveTargets(index)
      val inputResults = target.inputs.map(results)

      val targetDestPath = target.defCtx.staticEnclosing match{
        case Some(enclosing) =>
          val targetDestPath = workspacePath.resolve(
            jnio.Paths.get(enclosing.stripSuffix(enclosingBase.staticEnclosing.getOrElse("")))
          )
          deleteRec(targetDestPath)
          targetDestPath
        case None => jnio.Files.createTempDirectory(null)
      }





      results(target) = target.evaluate(
        new Args(inputResults.toIndexedSeq, targetDestPath)
      )
    }
    results(t).asInstanceOf[T]
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
