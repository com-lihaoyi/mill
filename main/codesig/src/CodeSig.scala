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

    val resolvedMethodCalls =
      MethodCallResolver.resolveAllMethodCalls(localSummary, externalSummary, logger)

    new CodeSig(
      localSummary,
      resolvedMethodCalls,
      localSummary.mapValues(_.methods.map{case (k, v) => (k, v.codeHash)})
    )
  }
}

class CodeSig(localSummary: LocalSummarizer.Result,
              val callToResolved: Map[MethodCall, Set[MethodDef]],
              methodHashes:  Map[JType.Cls, Map[MethodSig, Int]]){
//  pprint.log(directCallGraph.size)
//  pprint.log(directCallGraph.values.map(_.size).sum)
  val methodDefs = localSummary
    .items
    .flatMap{case (cls, cInfo) => cInfo.methods.map{case (m, mInfo) => MethodDef(cls, m)}}
    .toArray
    .distinct
    .sorted

  val methodCalls = callToResolved.keys

  val indexToNodes: Array[CallGraphNode] = (methodDefs ++ methodCalls).map(x => x: CallGraphNode)

  val nodeToIndex = indexToNodes.zipWithIndex.toMap

  val indexGraphEdges = indexToNodes
    .iterator
    .map {
      case methodCall: MethodCall => callToResolved(methodCall).flatMap(nodeToIndex.get(_))
      case methodDef: MethodDef =>
        localSummary
          .get(methodDef.cls, methodDef.method)
          .get
          .calls
          .map(nodeToIndex(_))
    }
    .toArray

  lazy val directCallGraph = {
    localSummary
      .items
      .iterator
      .flatMap { case (cls, clsInfo) =>
        clsInfo.methods.iterator.map { case (m0, methodInfo) =>
          val resolvedMethod = MethodDef(cls, m0)
          val resolved = methodInfo.calls
            .iterator
            .flatMap(callToResolved.getOrElse(_, Nil))
            .filter { m => localSummary.get(m.cls, m.method).nonEmpty }
            .toSet

          (resolvedMethod, resolved)
        }
      }
      .toMap
  }
//  pprint.log(indexToMethod.size)
//  pprint.log(indexGraphEdges.size)
  lazy val prettyGraph = directCallGraph.map{case (k, vs) => (k.toString, vs.map(_.toString).to(collection.SortedSet))}.to(collection.SortedMap)
  lazy val prettyHashes = methodHashes
    .flatMap{case (k, vs) =>
      vs.map{case (m, dests) => MethodDef(k, m).toString -> dests }
    }

  val topoSortedMethodGroups = Tarjans.apply(indexGraphEdges.map(x => x: Iterable[Int]))//.map(_.map(indexToMethod).toSet)
//  pprint.log(topoSortedMethodGroups.size)

  val transitiveCallGraphHashes = Util.computeTransitive[Int, Int](
    topoSortedMethodGroups.map(_.toSet),
    indexGraphEdges(_).toSet,
    methodIndex => {
      indexToNodes(methodIndex) match{
        case m: MethodCall => 0
        case m: MethodDef => methodHashes(m.cls)(m.method)
      }
    },
    _.hashCode()
  ).toMap
//  pprint.log(transitiveCallGraphHashes.size)
//  val transitiveCallGraphMethods = Util.computeTransitive[Set[ResolvedMethodDef]](
//    topoSortedMethodGroups,
//    directCallGraph,
//        Set(_),
//        _.flatten.toSet
//      ).map { case (k, vs) => (k, vs.filter(_ != k)) }


}
