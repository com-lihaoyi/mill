package hbt

import java.nio.{file => jnio}

import sourcecode.Enclosing

import scala.collection.mutable

object Evaluator{


  def apply[T](t: Target[T],
               workspacePath: jnio.Path)
              (implicit enclosing: Enclosing): T = {
    jnio.Files.createDirectories(workspacePath)
    val targetPaths = mutable.Map.empty[Target[_], List[String]]
    def rec(t: Target[_], path: List[String]): Unit = {
      if (targetPaths.contains(t)) () // do nothing
      else {
        val currentPath = t.defCtx.staticEnclosing match{
          case None => path.reverse
          case Some(s) => s.stripPrefix(enclosing.value).drop(1).split('.').toList
        }

        targetPaths(t) = currentPath
        t.inputs.zipWithIndex.foreach{case (c, i) => rec(c, i.toString :: currentPath)}
      }
    }
    rec(t, Nil)
    val targets = targetPaths.keys.toIndexedSeq
    val targetIndices = targets.zipWithIndex.toMap

    val numberedEdges =
      for(i <- targets.indices)
      yield targets(i).inputs.map(targetIndices)

    val sortedClusters = Tarjans(numberedEdges)
    val nonTrivialClusters = sortedClusters.filter(_.length > 1)
    assert(nonTrivialClusters.isEmpty, nonTrivialClusters)

    val results = mutable.Map.empty[Target[_], Any]
    for (cluster <- sortedClusters){
      val Seq(singletonIndex) = cluster
      val target = targets(singletonIndex)
      val inputResults = target.inputs.map(results)
      val targetDestPath = workspacePath.resolve(
        jnio.Paths.get(targetPaths(target).mkString("/"))
      )
      import collection.JavaConverters._
      if (jnio.Files.exists(targetDestPath)){
        jnio.Files.walk(targetDestPath).iterator()
          .asScala
          .toArray
          .reverseIterator
          .map(jnio.Files.deleteIfExists)
      }

      results(target) = target.evaluate(
        new Args(inputResults.toIndexedSeq, targetDestPath)
      )
    }
    results(t).asInstanceOf[T]
  }
}
