package mill.codesig
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

import java.net.URLClassLoader

object CodeSig{
  def compute(classFiles: Seq[os.Path],
              upstreamClasspath: Seq[os.Path]) = {
    val upstreamClasspathClassloader = new URLClassLoader(
      upstreamClasspath.map(_.toNIO.toUri.toURL).toArray,
      getClass.getClassLoader
    )
    val summary = LocalSummarizer.summarize(
      classFiles.map(p => loadClass(os.read.bytes(p)))
    )

    val allDirectAncestors = summary.directAncestors.flatMap(_._2)

    val allMethodCallParamClasses = summary
      .callGraph
      .flatMap(_._2.flatMap(_._2))
      .flatMap(_.desc.args)
      .collect { case c: JType.Cls => c }

    val external = ExternalSummarizer.loadAll(
      (allDirectAncestors ++ allMethodCallParamClasses)
        .filter(!summary.directAncestors.contains(_))
        .toSet,
      externalType =>
        loadClass(
          os.read.bytes(os.resource(upstreamClasspathClassloader) / os.SubPath(externalType.name.replace('.', '/') + ".class"))
        )
    )

    val foundTransitive0 = MethodCallResolver.resolveAllMethodCalls(summary, external)

    foundTransitive0
  }

  def loadClass(bytes: Array[Byte]) = {
    val classReader = new ClassReader(bytes)
    val classNode = new ClassNode()
    classReader.accept(classNode, 0)
    classNode
  }
}
class CodeSig{
  //    val methodToIndex = summary.callGraph.keys.toVector.zipWithIndex.toMap
  //    val indexToMethod = methodToIndex.map(_.swap)
  //    val topoSortedMethodGroups = Tarjans
  //      .apply(
  //        Range(0, methodToIndex.size).map(i => resolvedCalls(indexToMethod(i)).map(methodToIndex))
  //      )
  //      .map(_.map(indexToMethod))
  //
  //    val transitiveCallGraphHashes = computeTransitive[Int](
  //      topoSortedMethodGroups,
  //      resolvedCalls,
  //      summary.methodHashes(_),
  //      _.hashCode()
  //    )
  //
  //    val transitiveCallGraphMethods = computeTransitive[Set[MethodSig]](
  //      topoSortedMethodGroups,
  //      resolvedCalls,
  //      Set(_),
  //      _.flatten.toSet
  //    ).map { case (k, vs) => (k, vs.filter(_ != k)) }

  /**
   * Summarizes the transitive closure of the method call graph, using the given
   * [[methodValue]] and [[reduce]] functions to return a single value of [[T]].
   *
   * This is done in topological order, in order to allow us to memo-ize the
   * values computed for upstream methods when processing downstream methods,
   * avoiding the need to repeatedly re-compute them. Each Strongly Connected
   * Component is processed together and assigned the same final value, since
   * they all have the exact same transitive closure
   */
//  def computeTransitive[T](topoSortedMethodGroups: Seq[Seq[MethodDef]],
//                           resolvedCalls: Map[MethodDef, Set[MethodDef]],
//                           methodValue: MethodDef => T, reduce: Seq[T] => T) = {
//    val seen = collection.mutable.Map.empty[MethodDef, T]
//    for (methodGroup <- topoSortedMethodGroups) {
//      val groupUpstreamCalls = methodGroup
//        .flatMap(resolvedCalls)
//        .filter(!methodGroup.contains(_))
//
//      val upstreamValues: Seq[T] = groupUpstreamCalls.sorted.map(seen)
//      val groupValues: Seq[T] = methodGroup.sorted.map(methodValue)
//      for (method <- methodGroup) {
//        seen(method) = reduce(upstreamValues ++ groupValues)
//      }
//    }
//    seen
//  }
}
