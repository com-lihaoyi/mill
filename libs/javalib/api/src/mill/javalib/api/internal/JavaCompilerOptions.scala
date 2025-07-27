package mill.javalib.api.internal

/** Java compiler options, without the `-J` options (the Java runtime options). */
case class JavaCompilerOptions private (options: Seq[String]) {
  {
    val runtimeOptions = options.filter(_.startsWith("-J"))
    if (runtimeOptions.nonEmpty) throw new IllegalArgumentException(
      s"Providing Java runtime options to javac is not supported."
    )
  }
}
object JavaCompilerOptions {
  given rw: upickle.default.ReadWriter[JavaCompilerOptions] =
    summon[upickle.default.ReadWriter[Seq[String]]]
      .bimap(_.options, new JavaCompilerOptions(_))

  def empty: JavaCompilerOptions = new JavaCompilerOptions(Seq.empty)

  /** Constructs without filtering. */
  def unsafe(options: Seq[String]): JavaCompilerOptions = new JavaCompilerOptions(options)

  def apply(options: Seq[String]): (runtime: JavaRuntimeOptions, compiler: JavaCompilerOptions) = {
    val prefix = "-J"
    val (runtimeOptions0, compilerOptions) = options.partition(_.startsWith(prefix))
    val runtimeOptions = JavaRuntimeOptions(runtimeOptions0.map(_.drop(prefix.length)))
    (runtimeOptions, new JavaCompilerOptions(compilerOptions))
  }
}

/** Options that are passed to the Java runtime. */
case class JavaRuntimeOptions(options: Seq[String])
