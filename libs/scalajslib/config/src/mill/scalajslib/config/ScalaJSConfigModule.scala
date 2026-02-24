package mill.scalajslib.config

import mill.*
import mill.api.daemon.Result
import mill.scalajslib.{ScalaJSModule, TestScalaJSModule}
import mill.scalajslib.config.worker.*
import mill.scalajslib.api
import mill.scalajslib.worker.{api => workerApi}
import org.scalajs.ir.ScalaJSVersions
import org.scalajs.jsenv.Input
import org.scalajs.linker.{interface => sjs}
import sbt.testing.Framework

/**
 * Allows to compile Scala.js code with advanced configuration options
 *
 * This module allows to compile Scala.js code, like [[ScalaJSModule]], but
 * also allows to configure the Scala.js linker using Scala.js' own API. It
 * also allows to specify custom module initializers.
 *
 * == How to use ==
 *
 * Add dependencies towards Mill's scalajslib-config module and Scala.js'
 * linker and js-envs modules in your meta-build, in `mill-build/build.mill`,
 * like
 * {{{
 * import mill._
 * import mill.scalalib._
 *
 * object `package` extends mill.meta.MillBuildRootModule {
 *
 *   def scalaJSVersion = "1.20.2" // Put here the Scala.js version you'd like to use
 *
 *   def mvnDeps = Seq(
 *     mvn"com.lihaoyi::mill-libs-scalajslib-config-1:${mill.api.BuildInfo.millVersion}",
 *     mvn"org.scala-js:scalajs-linker_2.13:$scalaJSVersion",
 *     mvn"org.scala-js:scalajs-js-envs_2.13:1.4.0"
 *   )
 * }
 * }}}
 *
 * Then use [[ScalaJSConfigModule]] instead of [[ScalaJSModule]] when defining a Scala.js module.
 * Beware that `scalaJSVersion` must not be overridden: the Scala.js version is specified in the
 * meta-build like illustrated above.
 *
 * Keep in mind that adding the Scala.js linker or js-env modules in the meta-build like above
 * effectively prevents the use of other Scala.js versions in your whole build, even when using
 * [[ScalaJSModule]] directly and specifying another Scala.js version.
 */
trait ScalaJSConfigModule extends ScalaJSModule { outer =>

  override final def scalaJSVersion = ScalaJSVersions.current

  trait ScalaJSConfigTests extends ScalaJSTests with TestScalaJSConfigModule {
    override def scalaJSConfig = outer.scalaJSConfig
  }

  /**
   * Whether to automatically define a Scala.js module initializer for the main
   * class of this module.
   *
   * Defaults to true.
   */
  def scalaJSUseMainModuleInitializer: T[Boolean] = Task(true)

  /**
   * Scala.js module initializers to ask to the Scala.js linker
   */
  def moduleInitializers: Task[Seq[sjs.ModuleInitializer]] = Task.Anon {
    ScalaJSConfigModule.moduleInitializers(
      finalMainClassOpt().toOption,
      scalaJSUseMainModuleInitializer()
    )
  }

  override protected def linkTask(isFullLinkJS: Boolean, forceOutJs: Boolean): Task[api.Report] = {
    val configTask =
      if (isFullLinkJS) fullScalaJSConfig else fastScalaJSConfig
    Task.Anon {
      linkJs(
        worker = ScalaJSConfigWorkerExternalModule.scalaJSWorker(),
        toolsClasspath = scalaJSToolsClasspath(),
        runClasspath = runClasspath(),
        moduleInitializers = moduleInitializers(),
        forceOutJs = forceOutJs,
        testBridgeInit = false,
        isFullLinkJS = isFullLinkJS,
        importMap = scalaJSImportMap(),
        config = configTask()
      )
    }
  }

  private def toWorkerApi(moduleKind: api.ModuleKind): workerApi.ModuleKind = moduleKind match {
    case api.ModuleKind.NoModule => workerApi.ModuleKind.NoModule
    case api.ModuleKind.CommonJSModule => workerApi.ModuleKind.CommonJSModule
    case api.ModuleKind.ESModule => workerApi.ModuleKind.ESModule
  }

  private def toWorkerApi(esFeatures: api.ESFeatures): workerApi.ESFeatures = workerApi.ESFeatures(
    allowBigIntsForLongs = esFeatures.allowBigIntsForLongs,
    avoidClasses = esFeatures.avoidClasses,
    avoidLetsAndConsts = esFeatures.avoidLetsAndConsts,
    esVersion = esFeatures.esVersion match {
      case api.ESVersion.ES2015 => workerApi.ESVersion.ES2015
      case api.ESVersion.ES2016 => workerApi.ESVersion.ES2016
      case api.ESVersion.ES2017 => workerApi.ESVersion.ES2017
      case api.ESVersion.ES2018 => workerApi.ESVersion.ES2018
      case api.ESVersion.ES2019 => workerApi.ESVersion.ES2019
      case api.ESVersion.ES2020 => workerApi.ESVersion.ES2020
      case api.ESVersion.ES2021 => workerApi.ESVersion.ES2021
      case api.ESVersion.ES5_1 => workerApi.ESVersion.ES5_1
    }
  )

  private def toWorkerApi(moduleSplitStyle: api.ModuleSplitStyle): workerApi.ModuleSplitStyle =
    moduleSplitStyle match {
      case api.ModuleSplitStyle.FewestModules => workerApi.ModuleSplitStyle.FewestModules
      case api.ModuleSplitStyle.SmallestModules => workerApi.ModuleSplitStyle.SmallestModules
      case api.ModuleSplitStyle.SmallModulesFor(packages*) =>
        workerApi.ModuleSplitStyle.SmallModulesFor(packages*)
    }

  private def toWorkerApi(outputPatterns: api.OutputPatterns): workerApi.OutputPatterns = {
    workerApi.OutputPatterns(
      jsFile = outputPatterns.jsFile,
      sourceMapFile = outputPatterns.sourceMapFile,
      moduleName = outputPatterns.moduleName,
      jsFileURI = outputPatterns.jsFileURI,
      sourceMapURI = outputPatterns.sourceMapURI
    )
  }

  /**
   * Scala.js linker configuration
   */
  def scalaJSConfig: Task[sjs.StandardConfig] = Task.Anon {
    val sjsVersion = scalaJSVersion()

    // scalaJSVersion is final, and set to this value
    assert(sjsVersion == ScalaJSVersions.current)

    ScalaJSConfig.config(
      sjsVersion = sjsVersion,
      moduleSplitStyle = toWorkerApi(moduleSplitStyle()),
      esFeatures = toWorkerApi(esFeatures()),
      moduleKind = toWorkerApi(moduleKind()),
      scalaJSOptimizer = scalaJSOptimizer(),
      scalaJSSourceMap = scalaJSSourceMap(),
      patterns = toWorkerApi(scalaJSOutputPatterns()),
      useWebAssembly = scalaJSExperimentalUseWebAssembly()
    )
  }

  /**
   * Scala.js linker configuration for fast linking
   */
  def fastScalaJSConfig: Task[sjs.StandardConfig] =
    scalaJSConfig

  /**
   * Scala.js linker configuration for full (optimized) linking
   */
  def fullScalaJSConfig: Task[sjs.StandardConfig] = Task.Anon {

    val sjsVersion = scalaJSVersion()

    var config = scalaJSConfig()

    config = config
      .withSemantics(sjs.Semantics.Defaults.optimized)
      .withClosureCompilerIfAvailable(config.moduleKind != sjs.ModuleKind.ESModule)

    if (ScalaJSConfig.minorIsGreaterThanOrEqual(sjsVersion, 16))
      config = config.withMinify(scalaJSMinify())

    config
  }

  private[scalajslib] def linkJs(
      worker: ScalaJSConfigWorker,
      toolsClasspath: Seq[PathRef],
      runClasspath: Seq[PathRef],
      moduleInitializers: Seq[sjs.ModuleInitializer],
      forceOutJs: Boolean,
      testBridgeInit: Boolean,
      isFullLinkJS: Boolean,
      importMap: Seq[api.ESModuleImportMapping],
      config: sjs.StandardConfig
  )(using ctx: mill.api.TaskCtx): Result[api.Report] = {
    val outputPath = ctx.dest

    os.makeDir.all(ctx.dest)

    worker.rawLink(
      toolsClasspath = toolsClasspath,
      runClasspath = runClasspath,
      dest = outputPath.toIO,
      moduleInitializers = moduleInitializers,
      forceOutJs = forceOutJs,
      testBridgeInit = testBridgeInit,
      isFullLinkJS = isFullLinkJS,
      importMap = importMap,
      config = config
    ).map { sjsReport =>
      fromSjs(sjsReport, outputPath)
    }
  }

  private def fromSjs(moduleKind: sjs.ModuleKind): api.ModuleKind = moduleKind match {
    case sjs.ModuleKind.NoModule => api.ModuleKind.NoModule
    case sjs.ModuleKind.ESModule => api.ModuleKind.ESModule
    case sjs.ModuleKind.CommonJSModule => api.ModuleKind.CommonJSModule
  }

  private def fromSjs(report: sjs.Report, dest: os.Path): api.Report = {
    api.Report(
      publicModules =
        report.publicModules.map(module =>
          api.Report.Module(
            moduleID = module.moduleID,
            jsFileName = module.jsFileName,
            sourceMapName = module.sourceMapName,
            moduleKind = fromSjs(module.moduleKind)
          )
        ),
      dest = PathRef(dest)
    )
  }

  /**
   * Inputs to pass to the Scala.js runner
   */
  def scalaJsInputs: Task[Seq[Input]] = Task.Anon {
    ScalaJSConfigModule.jsEnvInputs(fastLinkJS())
  }

  override def run(args: Task[Args] = Task.Anon(Args())): Command[Unit] = Task.Command {
    if (args().value.nonEmpty) {
      Task.log.warn("Passing command line arguments to run is not supported by Scala.js.")
    }
    finalMainClassOpt() match {
      case Left(err) => Task.fail(err)
      case Right(_) =>
        ScalaJSConfigWorkerExternalModule.scalaJSWorker().run0(
          scalaJSToolsClasspath(),
          jsEnvConfig(),
          scalaJsInputs()
        )
        ()
    }
  }
}

private[mill] object ScalaJSConfigModule {

  /** Computes defaults inputs to pass to the Scala.js runner */
  def jsEnvInputs(report: api.Report): Seq[Input] = {
    val mainModule = report.publicModules.find(_.moduleID == "main").getOrElse(throw new Exception(
      "Cannot determine `jsEnvInput`: Linking result does not have a " +
        "module named `main`.\n" +
        s"Full report:\n$report"
    ))
    val path = (report.dest.path / mainModule.jsFileName).toNIO
    val input = mainModule.moduleKind match {
      case api.ModuleKind.NoModule => Input.Script(path)
      case api.ModuleKind.ESModule => Input.ESModule(path)
      case api.ModuleKind.CommonJSModule => Input.CommonJSModule(path)
    }
    Seq(input)
  }

  /** Computes default module initializers to ask to the Scala.js linker */
  def moduleInitializers(
      mainClassOpt: Option[String],
      scalaJSUseMainModuleInitializer: Boolean
  ): Seq[sjs.ModuleInitializer] =
    mainClassOpt match {
      case Some(main) if scalaJSUseMainModuleInitializer =>
        Seq(sjs.ModuleInitializer.mainMethodWithArgs(main, "main"))
      case _ =>
        Nil
    }
}

trait TestScalaJSConfigModule extends TestScalaJSModule with ScalaJSConfigModule {

  override protected def testLinkTask: Task[api.Report] = Task.Anon {
    linkJs(
      worker = ScalaJSConfigWorkerExternalModule.scalaJSWorker(),
      toolsClasspath = scalaJSToolsClasspath(),
      runClasspath = scalaJSTestDeps() ++ runClasspath(),
      moduleInitializers = testModuleInitializers(),
      forceOutJs = false,
      testBridgeInit = true,
      isFullLinkJS = false,
      importMap = scalaJSImportMap(),
      config = fastScalaJSConfig()
    )
  }

  /**
   * Scala.js module initializers to ask to the Scala.js linker for tests
   */
  def testModuleInitializers: Task[Seq[sjs.ModuleInitializer]] = Task.Anon(Nil)

  /**
   * Inputs to pass to the Scala.js runner to run tests
   */
  def scalaJsTestInputs: Task[Seq[Input]] = Task.Anon {
    ScalaJSConfigModule.jsEnvInputs(fastLinkJSTest())
  }

  def testFrameworkInstance: Task[(close: () => Unit, framework: Framework)] = Task.Anon {
    ScalaJSConfigWorkerExternalModule.scalaJSWorker().rawGetFramework(
      scalaJSToolsClasspath(),
      jsEnvConfig(),
      testFramework(),
      scalaJsTestInputs()
    )
  }

}
