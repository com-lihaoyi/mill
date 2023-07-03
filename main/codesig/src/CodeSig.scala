package mill.codesig
import mill.util.Tarjans
import upickle.default.{Writer, macroW, writer}
import java.net.URLClassLoader

object CodeSig {
  def compute(classFiles: Seq[os.Path], upstreamClasspath: Seq[os.Path], logger: Logger) = {

    val upstreamClasspathClassloader = new URLClassLoader(
      upstreamClasspath.map(_.toNIO.toUri.toURL).toArray,
      getClass.getClassLoader
    )
    val localSummary = logger {
      LocalSummarizer.summarize(classFiles.iterator.map(os.read.inputStream(_)))
    }

    val allDirectAncestors = localSummary.mapValuesOnly(_.directAncestors).flatten

    val allMethodCallParamClasses = localSummary
      .mapValuesOnly(_.methods.values)
      .flatten
      .flatMap(_.calls)
      .flatMap(call => Seq(call.cls) ++ call.desc.args)
      .collect { case c: JType.Cls => c }

    val externalSummary = logger {
      ExternalSummarizer.loadAll(
        (allDirectAncestors ++ allMethodCallParamClasses)
          .filter(!localSummary.contains(_))
          .toSet,
        externalType =>
          os.read.inputStream(os.resource(upstreamClasspathClassloader) / os.SubPath(
            externalType.name.replace('.', '/') + ".class"
          ))
      )
    }

    val resolvedMethodCalls = logger {
      MethodCallResolver.resolveAllMethodCalls(localSummary, externalSummary, logger)
    }

    new CodeSig(
      localSummary,
      resolvedMethodCalls,
      localSummary.mapValues(_.methods.map { case (k, v) => (k, v.codeHash) }),
      externalSummary,
      logger
    )
  }

  /**
   * Represents the three types of nodes in our call graph. These are kept heterogenous
   * because flattening them out into a homogenous graph of MethodDef -> MethodDef edges
   * results in a lot of duplication that bloats the size of the graph non-linearly with
   * the size of the program
   */
  sealed trait Node

  implicit def nodeRw: Writer[Node] = upickle.default.stringKeyW(
    writer[String].comap[Node] {
      case LocalDef(call) => "def " + call.toString
      case Call(call) => "call " + call.toString
      case ExternalClsCall(call) => call.toString
    }
  )

  case class LocalDef(call: MethodDef) extends Node
  case class Call(call: MethodCall) extends Node
  case class ExternalClsCall(call: JType.Cls) extends Node
}

class CodeSig(
    val localSummary: LocalSummarizer.Result,
    val resolved: MethodCallResolver.Result,
    val methodHashes: Map[JType.Cls, Map[MethodSig, Int]],
    val externalSummary: ExternalSummarizer.Result,
    logger: Logger
) {
//  pprint.log(directCallGraph.size)
//  pprint.log(directCallGraph.values.map(_.size).sum)
  val methodDefs = localSummary
    .items
    .flatMap { case (cls, cInfo) => cInfo.methods.map { case (m, mInfo) => MethodDef(cls, m) } }
    .toVector
    .distinct
    .sorted

  val methodCalls = resolved.localCalls.keys
  val externalClasses = externalSummary.directMethods.keys

  val indexToNodes: Vector[CodeSig.Node] =
    methodDefs.map(CodeSig.LocalDef(_)) ++
      methodCalls.map(CodeSig.Call(_)) ++
      externalClasses.map(CodeSig.ExternalClsCall(_))

  val nodeToIndex = indexToNodes.zipWithIndex.toMap

  val indexGraphEdges = indexToNodes
    .iterator
    .map {
      case CodeSig.Call(methodCall) =>
        val callInfo = resolved.localCalls(methodCall)
        val local = callInfo.localDests.map(CodeSig.LocalDef)
        val external = callInfo.externalDests.map(CodeSig.ExternalClsCall)
        local ++ external

      case CodeSig.LocalDef(methodDef) =>
        localSummary
          .get(methodDef.cls, methodDef.method)
          .get
          .calls
          .map(CodeSig.Call)

      case CodeSig.ExternalClsCall(externalCls) =>
        val local = resolved
          .externalClassLocalDests
          .get(externalCls)
          .iterator
          .flatMap { case (localClasses: Set[JType.Cls], localMethods: Set[MethodSig]) =>
            for {
              cls <- localClasses
              m <- localMethods
              if localSummary.get(cls, m).nonEmpty
            } yield CodeSig.LocalDef(MethodDef(cls, m))
          }
          .toSet

        val parent = externalSummary.directAncestors(externalCls).map(CodeSig.ExternalClsCall)
        local ++ parent
    }
    .map(_.map(nodeToIndex))
    .toVector

  logger {
    indexGraphEdges
      .zipWithIndex
      .map { case (dests, src) => indexToNodes(src) -> dests.map(indexToNodes) }
      .toMap
  }(implicitly, "indexGraphPrettyEdges")

  lazy val prettyHashes = methodHashes
    .flatMap { case (k, vs) =>
      vs.map { case (m, dests) => MethodDef(k, m).toString -> dests }
    }

  val topoSortedMethodGroups =
    Tarjans.apply(indexGraphEdges.map(x => x: Iterable[Int])) // .map(_.map(indexToMethod).toSet)

  val transitiveCallGraphHashes = Util.computeTransitive[Int, Int](
    topoSortedMethodGroups.map(_.toSet),
    indexGraphEdges(_).toSet,
    methodIndex => {
      indexToNodes(methodIndex) match {
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
        val (notDefined, defined) = ns.partitionMap(n =>
          transform.lift(n) match {
            case None => Left(n)
            case Some(v) => Right(v)
          }
        )

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
