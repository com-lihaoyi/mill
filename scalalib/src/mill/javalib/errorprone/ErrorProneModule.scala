package mill.javalib.errorprone

import mill.api.PathRef
import mill.scalalib.{Dep, DepSyntax, JavaModule}
import mill.{Agg, T, Task}

import java.io.File

/**
 * Integrated Error Prone into a [[JavaModule]].
 *
 * See https://errorprone.info/index
 */
trait ErrorProneModule extends JavaModule {

  /** The `error-prone` version to use. Defaults to [[BuildInfo.errorProneVersion]]. */
  def errorProneVersion: T[String] = Task.Input {
    mill.scalalib.api.Versions.errorProneVersion
  }

  /**
   * The dependencies of the `error-prone` compiler plugin.
   */
  def errorProneDeps: T[Agg[Dep]] = Task {
    Agg(
      mvn"com.google.errorprone:error_prone_core:${errorProneVersion()}"
    )
  }

  /**
   * The classpath of the `error-prone` compiler plugin.
   */
  def errorProneClasspath: T[Agg[PathRef]] = Task {
    defaultResolver().classpath(errorProneDeps())
  }

  /**
   * Options used to enable and configure the `error-prone` plugin in the Java compiler.
   */
  def errorProneJavacEnableOptions: T[Seq[String]] = Task {
    val processorPath = errorProneClasspath().map(_.path).mkString(File.pathSeparator)
    val enableOpts = Seq(
      "-XDcompilePolicy=simple",
      "-processorpath",
      processorPath,
      (Seq("-Xplugin:ErrorProne") ++ errorProneOptions()).mkString(" ")
    )
    val java17Options = Option.when(scala.util.Properties.isJavaAtLeast(16))(Seq(
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
    java17Options ++ enableOpts
  }

  /**
   * Options directly given to the `error-prone` processor.
   *
   * Those are documented as "flags" at https://errorprone.info/docs/flags
   */
  def errorProneOptions: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Appends the [[errorProneJavacEnableOptions]] to the Java compiler options.
   */
  override def mandatoryJavacOptions: T[Seq[String]] = Task {
    super.mandatoryJavacOptions() ++ errorProneJavacEnableOptions()
  }
}
