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

    val resolvedMethodCalls = logger {
      MethodCallResolver.resolveAllMethodCalls(localSummary, externalSummary, logger)
    }

    new CodeSig(
      localSummary,
      resolvedMethodCalls,
      localSummary.mapValues(_.methods.map{case (k, v) => (k, v.codeHash)})
    )
  }

  /**
   * Represents the three types of nodes in our call graph. These are kept heterogenous
   * because flattening them out into a homogenous graph of MethodDef -> MethodDef edges
   * results in a lot of duplication that bloats the size of the graph non-linearly with
   * the size of the program
   */
  sealed trait Node
  case class LocalDef(call: MethodDef) extends Node
  case class Call(call: MethodCall) extends Node
  case class ExternalClsCall(call: JType.Cls) extends Node
}

class CodeSig(val localSummary: LocalSummarizer.Result,
              val resolved: MethodCallResolver.Result,
              val methodHashes:  Map[JType.Cls, Map[MethodSig, Int]]){
//  pprint.log(directCallGraph.size)
//  pprint.log(directCallGraph.values.map(_.size).sum)
  val methodDefs = localSummary
    .items
    .flatMap{case (cls, cInfo) => cInfo.methods.map{case (m, mInfo) => MethodDef(cls, m)}}
    .toVector
    .distinct
    .sorted


  val methodCalls = resolved.localCalls.keys
  val externalClasses = resolved.externalClassLocalDests.keys

  val indexToNodes: Vector[CodeSig.Node] =
    methodDefs.map(CodeSig.LocalDef(_)) ++
    methodCalls.map(CodeSig.Call(_)) ++
    externalClasses.map(CodeSig.ExternalClsCall(_))

  val nodeToIndex = indexToNodes.zipWithIndex.toMap

  val indexGraphEdges = indexToNodes
    .iterator
    .map {
      case CodeSig.Call(methodCall) =>
        resolved.localCalls(methodCall).map(CodeSig.LocalDef) ++
        resolved.externalCalledClasses(methodCall).map(CodeSig.ExternalClsCall(_))

      case CodeSig.LocalDef(methodDef) =>
        localSummary
          .get(methodDef.cls, methodDef.method)
          .get
          .calls
          .map(CodeSig.Call)

      case CodeSig.ExternalClsCall(cls) =>
        resolved.externalClassLocalDests(cls).map(CodeSig.LocalDef)
    }
    .map(_.flatMap(nodeToIndex.get))
    .toVector


//  pprint.log(indexToMethod.size)
//  pprint.log(indexGraphEdges.size)
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
        case CodeSig.LocalDef(m) => methodHashes(m.cls)(m.method)
        case _ => 0
      }
    },
    _.hashCode()
  ).toMap

  def simplifiedCallGraph[T](transform: PartialFunction[CodeSig.Node, T]): Map[T, Set[T]] = {

    def flatten(ns: Set[CodeSig.Node]): Set[T] = {
      if (ns.isEmpty) Set()
      else {
        val (notDefined, defined) = ns.partitionMap(n => transform.lift(n) match {
          case None => Left(n)
          case Some(v) => Right(v)
        })

        val downstream = flatten(
          notDefined.flatMap(n => indexGraphEdges(nodeToIndex(n))).map(indexToNodes)
        )

        defined ++ downstream
      }
    }

    indexGraphEdges
      .zipWithIndex
      .flatMap { case (destIndices, srcIndex) =>
        transform.lift(indexToNodes(srcIndex))
          .map((_, flatten(destIndices.map(destIndex => indexToNodes(destIndex)))))
      }
      .toMap
  }

}
