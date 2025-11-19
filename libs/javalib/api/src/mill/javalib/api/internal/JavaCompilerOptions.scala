package mill.javalib.api.internal

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
}
