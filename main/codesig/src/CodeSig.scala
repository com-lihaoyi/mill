package mill.codesig
import mill.util.Tarjans
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

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
      LocalSummarizer.summarize(classFiles.map(p => loadClass(os.read.bytes(p))))
    }

    val allDirectAncestors = localSummary.directAncestors.flatMap(_._2)

    val allMethodCallParamClasses = localSummary
      .callGraph
      .flatMap(_._2.flatMap(_._2))
      .flatMap(call => Seq(call.cls) ++ call.desc.args)
      .collect { case c: JType.Cls => c }

    val externalSummary = logger{
      ExternalSummarizer.loadAll(
        (allDirectAncestors ++ allMethodCallParamClasses)
          .filter(!localSummary.directAncestors.contains(_))
          .toSet,
        externalType =>
          loadClass(
            os.read.bytes(os.resource(upstreamClasspathClassloader) / os.SubPath(externalType.name.replace('.', '/') + ".class"))
          )
      )
    }

    val directCallGraph = MethodCallResolver.resolveAllMethodCalls(localSummary, externalSummary, logger)

    new CodeSig(directCallGraph, localSummary.methodHashes)
  }

  def loadClass(bytes: Array[Byte]) = {
    val classReader = new ClassReader(bytes)
    val classNode = new ClassNode()
    classReader.accept(classNode, 0)
    classNode
  }
}

class CodeSig(val directCallGraph: Map[ResolvedMethodDef, Set[ResolvedMethodDef]],
              methodHashes:  Map[JType.Cls, Map[MethodDef, Int]]){
  val methodToIndex0 = directCallGraph.flatMap{case (k, vs) => Seq(k) ++ vs}.toVector.distinct.sorted.zipWithIndex
  val methodToIndex = methodToIndex0.toMap
  val indexToMethod = methodToIndex0.map(_.swap).toMap

  val indexGraphEdges = methodToIndex0
    .map { case (m, i) =>
      directCallGraph(m).map(methodToIndex)
    }

  val prettyGraph = directCallGraph.map{case (k, vs) => (k.toString, vs.map(_.toString).to(collection.SortedSet))}.to(collection.SortedMap)
  val prettyHashes = methodHashes
    .flatMap{case (k, vs) =>
      vs.map{case (m, dests) => ResolvedMethodDef(k, m).toString -> dests }
    }
//  pprint.log(prettyHashes)
  val topoSortedMethodGroups = Tarjans.apply(indexGraphEdges).map(_.map(indexToMethod))

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
