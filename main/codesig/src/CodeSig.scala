package mill.codesig
import mill.util.Tarjans

import java.net.URLClassLoader

object CodeSig{
  def compute(classFiles: Seq[os.Path],
              upstreamClasspath: Seq[os.Path],
              logger: Logger) = {

    val upstreamClasspathClassloader = new URLClassLoader(
      upstreamClasspath.map(_.toNIO.toUri.toURL).toArray,
      getClass.getClassLoader
    )
    val localSummary = logger{
      LocalSummarizer.summarize(classFiles.iterator.map(os.read.inputStream(_)))
    }

    val allDirectAncestors = localSummary.mapValuesOnly(_.directAncestors).flatten

    val allMethodCallParamClasses = localSummary
      .mapValuesOnly(_.methods.values)
      .flatten
      .flatMap(_.calls)
      .flatMap(call => Seq(call.cls) ++ call.desc.args)
      .collect { case c: JType.Cls => c }

    val externalSummary = logger{
      ExternalSummarizer.loadAll(
        (allDirectAncestors ++ allMethodCallParamClasses)
          .filter(!localSummary.contains(_))
          .toSet,
        externalType =>
          os.read.inputStream(os.resource(upstreamClasspathClassloader) / os.SubPath(externalType.name.replace('.', '/') + ".class"))
      )
    }

    val directCallGraph = MethodCallResolver.resolveAllMethodCalls(localSummary, externalSummary, logger)

    new CodeSig(
      directCallGraph,
      localSummary.mapValues(_.methods.map{case (k, v) => (k, v.codeHash)})
    )
  }
}

class CodeSig(val directCallGraph: Map[ResolvedMethodDef, Set[ResolvedMethodDef]],
              methodHashes:  Map[JType.Cls, Map[MethodDef, Int]]){
  val methodToIndex0 = directCallGraph.flatMap{case (k, vs) => Seq(k) ++ vs}.toVector.distinct.sorted.zipWithIndex
  val methodToIndex = methodToIndex0.toMap
  val indexToMethod = methodToIndex0.map(_.swap).toMap

  val indexGraphEdges = methodToIndex0
    .map { case (m, i) => directCallGraph(m).map(methodToIndex)}

  lazy val prettyGraph = directCallGraph.map{case (k, vs) => (k.toString, vs.map(_.toString).to(collection.SortedSet))}.to(collection.SortedMap)
  lazy val prettyHashes = methodHashes
    .flatMap{case (k, vs) =>
      vs.map{case (m, dests) => ResolvedMethodDef(k, m).toString -> dests }
    }

  val topoSortedMethodGroups = Tarjans.apply(indexGraphEdges).map(_.map(indexToMethod).toSet)

  val transitiveCallGraphHashes = Util.computeTransitive[ResolvedMethodDef, Int](
    topoSortedMethodGroups,
    directCallGraph,
    r => methodHashes(r.cls)(r.method),
    _.hashCode()
  ).toMap

//  val transitiveCallGraphMethods = Util.computeTransitive[Set[ResolvedMethodDef]](
//    topoSortedMethodGroups,
//    directCallGraph,
//        Set(_),
//        _.flatten.toSet
//      ).map { case (k, vs) => (k, vs.filter(_ != k)) }


}
