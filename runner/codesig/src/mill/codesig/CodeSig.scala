package mill.codesig

import mill.codesig.JvmModel.*

object CodeSig {

  /**
   * Get a CallGraphAnalysis without logging, suitable for testQuick command.
   */
  def getCallGraphAnalysis(
      classFiles: Seq[os.Path],
      upstreamClasspath: Seq[os.Path],
      ignoreCall: (Option[MethodDef], MethodSig) => Boolean
  ): CallGraphAnalysis = {
    given st: SymbolTable = new SymbolTable()

    val localSummary = LocalSummary.apply(classFiles.iterator.map(os.read.inputStream(_)))
    val externalSummary = ExternalSummary.apply(localSummary, upstreamClasspath)
    val resolvedMethodCalls = ResolvedCalls.apply(localSummary, externalSummary)

    new CallGraphAnalysis(
      localSummary,
      resolvedMethodCalls,
      externalSummary,
      ignoreCall,
      logger = null,
      prevTransitiveCallGraphHashesOpt = () => None
    )
  }

  def compute(
      classFiles: Seq[os.Path],
      upstreamClasspath: Seq[os.Path],
      ignoreCall: (Option[MethodDef], MethodSig) => Boolean,
      logger: Logger,
      prevTransitiveCallGraphHashesOpt: () => Option[Map[String, Int]]
  ): CallGraphAnalysis = {
    given st: SymbolTable = new SymbolTable()

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
      logger,
      prevTransitiveCallGraphHashesOpt
    )
  }
}
