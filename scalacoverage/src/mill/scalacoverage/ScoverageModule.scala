package mill.scalacoverage
import ammonite.ops.Path
import coursier.Cache
import coursier.maven.MavenRepository
import mill.define.{Sources, Target, Task}
import mill.eval.Result
import mill.scalalib.{Lib, ScalaModule, _}
import mill.{Agg, PathRef, T}
import mill.util.JsonFormatters.pathReadWrite

object ScoverageOptions {
  val OrgScoverage = "org.scoverage"
  val ScalacRuntimeArtifact = "scalac-scoverage-runtime"
  val ScalacPluginArtifact = "scalac-scoverage-plugin"
  val DefaultScoverageVersion = "1.4.0-M3"
}


trait ScoverageOptions {
  import ScoverageOptions._

  /** The version of scoverage. */
  def scoverageVersion: String = DefaultScoverageVersion

  /** A sequence of packages to exclude from coverage. */
  def excludedPackages: Seq[String] = Seq.empty

  /** A sequence of files to exclude from coverage. */
  def excludedFiles: Seq[String] = Seq.empty

  def highlighting: Boolean = true

  /** The minimum coverage. */
  def minimum: Double = 0

  /** Fail the build if the minimum coverage is not met. */
  def failOnMinimum: Boolean = false

  /** True to output a Cobertura coverage report. */
  def outputCobertura: Boolean = true

  /** True to output an XML coverage report. */
  def outputXML: Boolean = true

  /** True to output an HTML coverage report. */
  def outputHTML: Boolean = true

  /** True to output debugging information to the HTML report. */
  def outputDebug: Boolean = false

  //def outputTeamCity: Boolean = false // FIXME

  /** True to clean coverage data after writing the reports. */
  def cleanSubprojectFiles: Boolean = true
}


trait ScoverageReportModule extends ScoverageModule {
  /** The target to generate the coverage report. */
  def coverageReport: T[Path] = T {
    val reportDir = coverageReportDir()
    scalaCoverageWorker().coverageReport(
      options                  = this,
      dataDir                  = coverageDataDir(),
      reportDir                = reportDir,
      compileSourceDirectories = sources().filter(_.path.toNIO.toFile.isDirectory).map(_.path),
      encoding                 = encoding(),
      log                      = T.ctx.log
    )
    reportDir
  }
}


trait ScoverageAggregateModule extends ScoverageModule {

  /** The set of coverage modules to aggregate. */
  def coverageModules: Seq[ScoverageModule]

  /** The sources across modules. */
  override def sources: Sources = T.sources{ Task.traverse(coverageModules)(_.sources)().flatten }

  /** The set of report directories, one per module. */
  private def coverageReportDirs: T[Seq[Path]] = T{ Task.traverse(coverageModules)(_.coverageReportDir)() }

  override def moduleDeps = coverageModules

  /** The target to generate the coverage report. */
  def aggregateCoverage: T[Path] = T {
    val aggregateReportDir = coverageReportDir()
    scalaCoverageWorker().aggregateCoverage(
      options                  = this,
      coverageReportDirs       = coverageReportDirs(),
      aggregateReportDir       = aggregateReportDir,
      compileSourceDirectories = sources().filter(_.path.toNIO.toFile.isDirectory).map(_.path),
      encoding                 = encoding(),
      log                      = T.ctx.log
    )
    aggregateReportDir
  }
}


trait ScoverageModule extends ScalaModule with ScoverageOptions {
  import ScoverageOptions._

  /** The path to where the coverage target should write its data. */
  def coverageDataDir: T[Path] = T{ T.ctx().dest }

  /** The path to where the coverage data should be written */
  def coverageReportDir: T[Path] = T{ coverageDataDir() }

  /** The specific test dependencies (ex. "org.scalatest::scalatest:3.0.1"). */
  def testIvyDeps: T[Agg[Dep]]

  protected def _scoverageVersion = T { scoverageVersion }

  /** The encoding parsed from the [[scalacOptions]]. */
  protected def encoding: T[Option[String]] = T{
    val options = scalacOptions()
    val i = options.indexOf("-encoding") + 1
    if (i > 0 && i < options.length) Some(options(i)) else None
  }

  /** The dependencies for scoverage during runtime. */
  private def runtimeIvyDeps = T { Seq(ivy"${OrgScoverage}::${ScalacRuntimeArtifact}:${_scoverageVersion()}") }

  /** The dependencies for scoverage's plugin. */
  private def pluginIvyDeps = T{ Seq(ivy"${OrgScoverage}::${ScalacPluginArtifact}:${_scoverageVersion()}") }

  /** The path to the scoverage plugin found via the plugin classpath. */
  private def pluginPath: T[Path] = T {
    val cp = scalacPluginClasspath()
    cp.find { pathRef =>
      val fileName = pathRef.path.toNIO.getFileName.toString
      fileName.toString.contains(ScalacPluginArtifact) && fileName.toString
        .contains(_scoverageVersion())
    }.getOrElse {
      throw new Exception(
        s"Fatal: ${ScalacPluginArtifact} (${_scoverageVersion()}) not found on the classpath:\n\t" + cp
          .map(_.path)
          .mkString("\n\t"))
    }.path
  }

  // Adds the runtime coverage dependencies
  final override def ivyDeps = super.ivyDeps() ++ runtimeIvyDeps() ++ testIvyDeps()

  // Adds the runtime coverage dependencies
  override def transitiveIvyDeps: T[Agg[Dep]] = T {
    ivyDeps() ++ runtimeIvyDeps() ++ Task.traverse(moduleDeps)(_.transitiveIvyDeps)().flatten
  }

  // Adds the plugin coverage dependencies
  override def scalacPluginIvyDeps = super.scalacPluginIvyDeps() ++ pluginIvyDeps()

  // Gets the major and minor version of the scoverage
  private def scoverageBinaryVersion = T{ _scoverageVersion().split('.').take(2).mkString(".") }

  /** Builds a new worker for coverage tasks. */
  protected def scalaCoverageWorker: Task[ScoverageWorkerApi] = T.task{
    ScoverageWorkerApi.scoverageWorker().impl(bridgeFullClassPath())
  }

  /** Adds the worker dependencies to the classpath. */
  private def scoverageWorkerClasspath = T {
    val workerKey = "MILL_SCALACOVERAGE_WORKER_" + scoverageBinaryVersion().replace('.', '_').replace('-', '_')
    val workerPath = sys.props(workerKey)
    if (workerPath != null)
      Result.Success(Agg(workerPath.split(',').map(p => PathRef(Path(p), quick = true)): _*))
    else
      Lib.resolveDependencies(
        Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
        Lib.depToDependency(_, "2.12.4", ""),
        Seq(ivy"com.lihaoyi::mill-scalacoverage-worker-${scoverageBinaryVersion()}:${sys.props("MILL_VERSION")}")
      )
  }

  // Adds the plugin path and report directory path to the set of scalac options.
  override def scalacOptions = T {
    super.scalacOptions() ++ Seq(
      Some(s"-Xplugin:${pluginPath()}"),
      Some(s"-P:scoverage:dataDir:${coverageReportDir().toNIO}"),
      Option(excludedPackages).map(v => s"-P:scoverage:excludedPackages:$v"),
      Option(excludedFiles).map(v => s"-P:scoverage:excludedFiles:$v"),
      if (highlighting) Some("-Yrangepos") else None // rangepos is broken in some releases of scala so option to turn it off
    ).flatten
  }

  private def bridgeFullClassPath: T[Seq[Path]] = T {
    Lib.resolveDependencies(
      Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2")),
      Lib.depToDependency(_, scalaVersion(), platformSuffix()),
      runtimeIvyDeps()
    ).map(t => (scoverageWorkerClasspath().toSeq ++ t.toSeq).map(_.path))
  }
}