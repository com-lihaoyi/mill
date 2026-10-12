package mill.javalib.errorprone

import mill.api.PathRef
import mill.javalib.{Dep, DepSyntax, JavaModule}
import mill.util.Version
import mill.{T, Task}
import mill.api.opt.*

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
   * Whether to include the `NullAway` compiler plugin dependencies.
   */
  def errorProneUseNullAway: T[Boolean] = Task { false }

  /**
   * The `NullAway` version to use.
   */
  def errorProneNullAwayVersion: T[String] = Task { "0.13.1" }

  /**
   * Optional `NullAway` plugin dependency.
   */
  def errorProneNullAwayMvnDeps: T[Seq[Dep]] = Task {
    if (errorProneUseNullAway()) Seq(mvn"com.uber.nullaway:nullaway:${errorProneNullAwayVersion()}")
    else Seq()
  }

  /**
   * Whether to include the Picnic Error Prone Support compiler plugin dependencies.
   */
  def errorProneUsePicnic: T[Boolean] = Task { false }

  /**
   * The Picnic Error Prone Support version to use.
   */
  def errorPronePicnicVersion: T[String] = Task { "0.25.0" }

  /**
   * Optional Picnic plugin dependencies.
   */
  def errorPronePicnicMvnDeps: T[Seq[Dep]] = Task {
    if (errorProneUsePicnic()) {
      Seq(
        mvn"tech.picnic.error-prone-support:error-prone-contrib:${errorPronePicnicVersion()}",
        mvn"tech.picnic.error-prone-support:refaster-runner:${errorPronePicnicVersion()}"
      )
    } else Seq()
  }

  /**
   * The dependencies of the `error-prone` compiler plugin.
   */
  def errorProneDeps: T[Seq[Dep]] = Task {
    Seq(
      mvn"com.google.errorprone:error_prone_core:${errorProneVersion()}"
    ) ++ errorProneNullAwayMvnDeps() ++ errorPronePicnicMvnDeps()
  }

  /**
   * The classpath of the `error-prone` compiler plugin.
   */
  def errorProneClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(
      errorProneDeps() ++ annotationProcessorsMvnDeps(),
      boms = allBomDeps()
    )
  }

  // Avoid duplicate -processorpath
  override def annotationProcessorsJavacOptions: Task.Simple[Opts] = Task {
    Opts()
  }

  /**
   * Options used to enable and configure the `error-prone` plugin in the Java compiler.
   */
  def errorProneJavacEnableOptions: T[Opts] = Task {
    // ErrorProne 2.36.0+ requires explicit --should-stop policy
    // See https://github.com/com-lihaoyi/mill/issues/4926
    val errorProne236Options =
      Opts.when(Version.isAtLeast(errorProneVersion(), "2.36.0")(using
        Version.IgnoreQualifierOrdering
      ))("--should-stop=ifError=FLOW")

    val processorPath = Opt.mkPlatformPath(errorProneClasspath().map(_.path))

    val enableOpts = Opts(
      "-XDcompilePolicy=simple",
      OptGroup(
        "-processorpath",
        processorPath
      ),
      // -Xplugin expects all params as a single arg, space-separated
      (Seq("-Xplugin:ErrorProne") ++ errorProneOptions()).mkString(" ")
    )
    errorProne236Options ++ enableOpts
  }

  private def errorProneJvmOptions: T[Opts] = Task {
    Opts.when(scala.util.Properties.isJavaAtLeast(16))(
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
    )
  }

  /**
   * JVM options used by the Java compiler worker when running ErrorProne.
   */
  private[mill] override def javaCompilerRuntimeOptions: T[Opts] = Task {
    jvmOptions() ++ errorProneJvmOptions()
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
  override def mandatoryJavacOptions: Task.Simple[Opts] = Task {
    super.mandatoryJavacOptions() ++ errorProneJavacEnableOptions()
  }
}
