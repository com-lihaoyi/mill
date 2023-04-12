package mill.codesig
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

object CodeSig{
  def compute(classFiles: Seq[os.Path]) = {
    val summary = LocalSummarizer.summarize(
      classFiles.map(p => loadClass(os.read.bytes(p)))
    )

    val allDirectAncestors = summary.directAncestors.flatMap(_._2)

    val allMethodCallParamClasses = summary
      .callGraph
      .flatMap(_._2)
      .flatMap(_.desc.args)
      .collect { case c: JType.Cls => c }

    val external = ExternalSummarizer.loadAll(
      (allDirectAncestors ++ allMethodCallParamClasses)
        .filter(!summary.directAncestors.contains(_))
        .toSet,
      externalType =>
        loadClass(os.read.bytes(os.resource / os.SubPath(externalType.name.replace('.', '/') + ".class")))
    )

    val foundTransitive0 = Analyzer.analyze(summary, external)

    foundTransitive0
  }

  def loadClass(bytes: Array[Byte]) = {
    val classReader = new ClassReader(bytes)
    val classNode = new ClassNode()
    classReader.accept(classNode, 0)
    classNode
  }
}
