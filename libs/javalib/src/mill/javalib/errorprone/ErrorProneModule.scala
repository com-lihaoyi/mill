package mill.javalib.errorprone

import mill.api.PathRef
import mill.api.opt.*
import mill.javalib.{Dep, DepSyntax, JavaModule}
import mill.{T, Task}

/**
 * Integrated Error Prone into a [[JavaModule]].
 *
 * See https://errorprone.info/index
 */
trait ErrorProneModule extends JavaModule {

  /** The `error-prone` version to use. Defaults to [[BuildInfo.errorProneVersion]]. */
  def errorProneVersion: T[String] = Task.Input {
    mill.javalib.api.Versions.errorProneVersion
  }

  /**
   * The dependencies of the `error-prone` compiler plugin.
   */
  def errorProneDeps: T[Seq[Dep]] = Task {
    Seq(
      mvn"com.google.errorprone:error_prone_core:${errorProneVersion()}"
    )
  }

  /**
   * The classpath of the `error-prone` compiler plugin.
   */
  def errorProneClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(errorProneDeps())
  }

  /**
   * Options used to enable and configure the `error-prone` plugin in the Java compiler.
   */
  def errorProneJavacEnableOptions: T[Opts] = Task {
    val processorPath: Opt = Opt.mkPath(errorProneClasspath().map(_.path), sep = java.io.File.pathSeparator)

    val enableOpts: Opts = Opts(
      "-XDcompilePolicy=simple",
      OptGroup("-processorpath", processorPath),
      // ErrorProne options are given as a single argument containing spaces
      (Seq("-Xplugin:ErrorProne") ++ errorProneOptions()).mkString(" ")
    )
    val java17Options: Seq[String] = Option.when(scala.util.Properties.isJavaAtLeast(16))(Seq(
      "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
      "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
      "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
      "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED"
    ).map(o => s"-J${o}")).toSeq.flatten

    Opts(java17Options) ++ enableOpts
  }

  /**
   * Options directly given to the `error-prone` processor.
   *
   * Those are documented as "flags" at https://errorprone.info/docs/flags
   */
  def errorProneOptions: T[Seq[String]] = Task { Seq() }

  /**
   * Appends the [[errorProneJavacEnableOptions]] to the Java compiler options.
   */
  override def mandatoryJavacOptions: T[Opts] = Task {
    super.mandatoryJavacOptions() ++ errorProneJavacEnableOptions()
  }
}
