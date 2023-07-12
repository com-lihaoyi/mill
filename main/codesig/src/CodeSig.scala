package mill.codesig
import JvmModel._

object CodeSig {
  def compute(classFiles: Seq[os.Path],
              upstreamClasspath: Seq[os.Path],
              ignoreCall: MethodSig => Boolean,
              logger: Logger) = {
    implicit val st: SymbolTable = new SymbolTable()

    val localSummary = LocalSummary.apply(classFiles.iterator.map(os.read.inputStream(_)))
    logger.log(localSummary)

    val externalSummary = ExternalSummary.apply(localSummary, upstreamClasspath)
    logger.log(externalSummary)

    val resolvedMethodCalls = ResolvedCalls.apply(localSummary, externalSummary)
    logger.log(resolvedMethodCalls)

    new CallGraphAnalysis(
      localSummary,
      resolvedMethodCalls,
      externalSummary,
      ignoreCall,
      logger
    )
  }
}
