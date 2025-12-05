package mill.javalib.codesig

import mill.codesig.{CodeSig, Logger}
import mill.codesig.JvmModel.{Desc, MethodSig}

/**
 * Worker implementation that computes method code hash signatures using Mill's codesig module.
 * This provides fine-grained bytecode-level change detection for testQuick.
 */
class CodeSigWorker extends CodeSigWorkerApi {

  /**
   * Computes transitive call graph hashes for compiled class files.
   *
   * @param classFiles Paths to compiled .class files
   * @param upstreamClasspath Paths to upstream dependency JARs/directories
   * @param logDir Optional directory for debug logging
   * @param prevHashesOpt Previous hashes for incremental computation
   * @return Map from method signature to its transitive call graph hash
   */
  override def computeCodeSignatures(
      classFiles: Seq[os.Path],
      upstreamClasspath: Seq[os.Path],
      logDir: Option[os.Path],
      prevHashesOpt: Option[Map[String, Int]]
  ): Map[String, Int] = {
    if (classFiles.isEmpty) return Map.empty

    val analysis = CodeSig.compute(
      classFiles = classFiles,
      upstreamClasspath = upstreamClasspath,
      ignoreCall = defaultIgnoreCall,
      logger = new Logger(
        logDir.getOrElse(os.temp.dir()),
        logDir
      ),
      prevTransitiveCallGraphHashesOpt = () => prevHashesOpt
    )

    analysis.transitiveCallGraphHashes.toMap
  }

  /**
   * Computes direct method code hashes (without transitive dependencies).
   *
   * @param classFiles Paths to compiled .class files
   * @param upstreamClasspath Paths to upstream dependency JARs/directories
   * @return Map from method signature to its direct code hash
   */
  override def computeMethodHashes(
      classFiles: Seq[os.Path],
      upstreamClasspath: Seq[os.Path]
  ): Map[String, Int] = {
    if (classFiles.isEmpty) return Map.empty

    val analysis = CodeSig.compute(
      classFiles = classFiles,
      upstreamClasspath = upstreamClasspath,
      ignoreCall = defaultIgnoreCall,
      logger = new Logger(os.temp.dir(), None),
      prevTransitiveCallGraphHashesOpt = () => None
    )

    analysis.methodCodeHashes.toMap
  }

  /**
   * Default filter for ignoring certain method calls in the call graph.
   * This is simpler than the build.mill version since we're analyzing
   * user code, not Mill task definitions.
   */
  private def defaultIgnoreCall(callSiteOpt: Option[mill.codesig.JvmModel.MethodDef], calledSig: MethodSig): Boolean = {
    // Don't ignore any calls by default for test code analysis
    // User code dependencies should all be tracked
    false
  }
}
