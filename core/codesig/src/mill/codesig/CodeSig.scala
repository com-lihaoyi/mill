package mill.codesig

import mill.codesig.JvmModel.*

object CodeSig {

  private def callGraphAnalysis(
    classFiles: Seq[os.Path],
    upstreamClasspath: Seq[os.Path],
    ignoreCall: (Option[MethodDef], MethodSig) => Boolean
  )(implicit st: SymbolTable): CallGraphAnalysis = {
    val localSummary = LocalSummary.apply(classFiles.iterator.map(os.read.inputStream(_)))

    val externalSummary = ExternalSummary.apply(localSummary, upstreamClasspath)

    val resolvedMethodCalls = ResolvedCalls.apply(localSummary, externalSummary)

    new CallGraphAnalysis(
      localSummary,
      resolvedMethodCalls,
      externalSummary,
      ignoreCall
    )
  }

  def getCallGraphAnalysis(
    classFiles: Seq[os.Path],
    upstreamClasspath: Seq[os.Path],
    ignoreCall: (Option[MethodDef], MethodSig) => Boolean
  ): CallGraphAnalysis = {
    implicit val st: SymbolTable = new SymbolTable()

    callGraphAnalysis(classFiles, upstreamClasspath, ignoreCall)
  }

  def compute(
      classFiles: Seq[os.Path],
      upstreamClasspath: Seq[os.Path],
      ignoreCall: (Option[MethodDef], MethodSig) => Boolean,
      logger: Logger,
      prevTransitiveCallGraphHashesOpt: () => Option[Map[String, Int]]
  ): CallGraphAnalysis = {
    implicit val st: SymbolTable = new SymbolTable()
    
    val callAnalysis = callGraphAnalysis(classFiles, upstreamClasspath, ignoreCall)

    logger.log(callAnalysis.localSummary)
    logger.log(callAnalysis.externalSummary)
    logger.log(callAnalysis.resolved)

    logger.mandatoryLog(callAnalysis.methodCodeHashes)
    logger.mandatoryLog(callAnalysis.prettyCallGraph)
    logger.mandatoryLog(callAnalysis.transitiveCallGraphHashes0)

    logger.log(callAnalysis.transitiveCallGraphHashes)

    val spanningInvalidationTree = callAnalysis.calculateSpanningInvalidationTree {
      prevTransitiveCallGraphHashesOpt()
    }

    logger.mandatoryLog(spanningInvalidationTree)

    callAnalysis
  }
}
