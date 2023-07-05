package mill.codesig
import JvmModel._

object CodeSig {
  def compute(classFiles: Seq[os.Path], upstreamClasspath: Seq[os.Path], logger: Logger) = {
    implicit val st: SymbolTable = new SymbolTable()

    val localSummary = LocalSummarizer.apply(classFiles.iterator.map(os.read.inputStream(_)))
    logger.log(localSummary)

    val externalSummary = ExternalSummarizer.apply(localSummary, upstreamClasspath)
    logger.log(externalSummary)

    val resolvedMethodCalls = MethodCallResolver.apply(localSummary, externalSummary)
    logger.log(resolvedMethodCalls)

    new CallGraphAnalysis(
      localSummary,
      resolvedMethodCalls,
      externalSummary,
      logger
    )
  }
}
