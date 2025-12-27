package mill.javalib.codesig

/**
 * API for computing bytecode-level method code signatures using Mill's codesig module.
 * Used by testQuick for fine-grained change detection at the method level.
 */
trait CodeSigWorkerApi extends AutoCloseable {

  /**
   * Computes transitive call graph hashes for compiled class files.
   * These hashes capture both the method's own bytecode and all methods it transitively calls.
   *
   * @param classFiles Paths to compiled .class files
   * @param upstreamClasspath Paths to upstream dependency JARs/directories
   * @param logDir Optional directory for debug logging
   * @param prevHashesOpt Previous hashes for incremental computation
   * @return Map from method signature to its transitive call graph hash
   */
  def computeCodeSignatures(
      classFiles: Seq[os.Path],
      upstreamClasspath: Seq[os.Path],
      logDir: Option[os.Path],
      prevHashesOpt: Option[Map[String, Int]]
  ): Map[String, Int]

  /**
   * Computes direct method code hashes (without transitive dependencies).
   *
   * @param classFiles Paths to compiled .class files
   * @param upstreamClasspath Paths to upstream dependency JARs/directories
   * @return Map from method signature to its direct code hash
   */
  def computeMethodHashes(
      classFiles: Seq[os.Path],
      upstreamClasspath: Seq[os.Path]
  ): Map[String, Int]

  override def close(): Unit = {
    // noop by default
  }
}

object CodeSigWorkerApi {

  /**
   * Extracts class name from a codesig method signature.
   * Format: "def package.ClassName#methodName(args)returnType"
   * or "package.ClassName.methodName(args)returnType" for static methods
   */
  def extractClassName(methodSig: String): Option[String] = {
    val sig = methodSig.stripPrefix("def ").stripPrefix("call ").stripPrefix("external ")
    val hashIdx = sig.indexOf('#')
    val parenIdx = sig.indexOf('(')

    if (hashIdx > 0) {
      // Instance method: "package.ClassName#method"
      Some(sig.substring(0, hashIdx))
    } else if (parenIdx > 0) {
      // Static method: "package.ClassName.method(args)"
      val lastDot = sig.lastIndexOf('.', parenIdx)
      if (lastDot > 0) Some(sig.substring(0, lastDot))
      else None
    } else {
      None
    }
  }

  /**
   * Groups method signatures by class name.
   */
  def groupByClass(signatures: Map[String, Int]): Map[String, Map[String, Int]] = {
    signatures.groupBy { case (sig, _) =>
      extractClassName(sig).getOrElse("unknown")
    }
  }

  /**
   * Computes a single hash for a class by combining all its method hashes.
   */
  def computeClassHash(methodHashes: Map[String, Int]): Int = {
    // Use XOR to make hash order-independent
    methodHashes.toSeq.sortBy(_._1).foldLeft(0) { case (acc, (sig, hash)) =>
      acc ^ (sig.hashCode * 31 + hash)
    }
  }

  /**
   * Converts method-level signatures to class-level signatures.
   */
  def toClassSignatures(methodSignatures: Map[String, Int]): Map[String, Int] = {
    groupByClass(methodSignatures).map { case (className, methods) =>
      className -> computeClassHash(methods)
    }
  }
}
