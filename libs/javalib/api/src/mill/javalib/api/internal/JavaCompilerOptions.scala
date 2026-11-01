package mill.javalib.api.internal

import mill.api.daemon.internal.OptsApi

object JavaCompilerOptions {

  /**
   * Extract JVM (=runtime) options out of the compiler options.
   * JVM options always start with `-J`.
   * The resulting `runtime` options have the `-J` prefix already stripped.
   */
  def split(options: Seq[String]): (runtime: Seq[String], compiler: Seq[String]) = {
    val prefix = "-J"
    val (runtimeOptions0, compilerOptions) = options.partition(_.startsWith(prefix))
    val runtimeOptions = runtimeOptions0.map(_.drop(prefix.length))
    (runtimeOptions, compilerOptions)
  }

  def split(options: OptsApi): (runtime: Seq[String], compiler: Seq[String]) =
    split(options.toStringSeq)
}
