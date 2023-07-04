package mill.codesig
import JvmModel._
import java.net.URLClassLoader

object CodeSig {
  def compute(classFiles: Seq[os.Path], upstreamClasspath: Seq[os.Path], logger: Logger) = {

    implicit val st: SymbolTable = new SymbolTable()

    val upstreamClasspathClassloader = new URLClassLoader(
      upstreamClasspath.map(_.toNIO.toUri.toURL).toArray,
      getClass.getClassLoader
    )
    val localSummary =
      LocalSummarizer.summarize(classFiles.iterator.map(os.read.inputStream(_)))

    logger.log(localSummary)

    val allDirectAncestors = localSummary.mapValuesOnly(_.directAncestors).flatten

    val allMethodCallParamClasses = localSummary
      .mapValuesOnly(_.methods.values)
      .flatten
      .flatMap(_.calls)
      .flatMap(call => Seq(call.cls) ++ call.desc.args)
      .collect { case c: JType.Cls => c }

    val externalSummary = ExternalSummarizer.loadAll(
      (allDirectAncestors ++ allMethodCallParamClasses)
        .filter(!localSummary.contains(_))
        .toSet,
      externalType =>
        os.read.inputStream(os.resource(upstreamClasspathClassloader) / os.SubPath(
          externalType.name.replace('.', '/') + ".class"
        ))
    )

    logger.log(externalSummary)

    val resolvedMethodCalls = MethodCallResolver
      .resolveAllMethodCalls(localSummary, externalSummary)

    logger.log(resolvedMethodCalls)

    new CallGraphAnalysis(
      localSummary,
      resolvedMethodCalls,
      localSummary.mapValues(_.methods.map { case (k, v) => (k, v.codeHash) }),
      externalSummary,
      logger
    )
  }
}
