package mill.scalajslib.worker

import mill.*
import mill.scalajslib.api
import mill.scalajslib.worker.{api => workerApi}
import mill.api.TaskCtx
import mill.api.Result
import mill.api.daemon.internal.internal
import mill.api.Discover
import mill.util.CachedFactory

import java.io.File
import java.net.URLClassLoader

@internal
private[scalajslib] class ScalaJSWorker(jobs: Int)
    extends CachedFactory[Seq[mill.PathRef], (URLClassLoader, workerApi.ScalaJSWorkerApi)] {

  override def setup(key: Seq[PathRef]) = {
    val cl = mill.util.Jvm.createClassLoader(
      key.map(_.path).toVector,
      getClass.getClassLoader,
      sharedPrefixes = Seq("sbt.testing.", "mill.api.daemon.internal.TestReporter")
    )
    val bridge = cl
      .loadClass("mill.scalajslib.worker.ScalaJSWorkerImpl")
      .getDeclaredConstructor(classOf[Int])
      .newInstance(jobs)
      .asInstanceOf[workerApi.ScalaJSWorkerApi]

    (cl, bridge)
  }

  override def teardown(
      key: Seq[PathRef],
      value: (URLClassLoader, workerApi.ScalaJSWorkerApi)
  ): Unit = {
    val (classloader, workerApi) = value
    workerApi.close()
    classloader.close()
  }

  override def maxCacheSize: Int = jobs

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

  private def toWorkerApi(jsEnvConfig: api.JsEnvConfig): workerApi.JsEnvConfig = {
    jsEnvConfig match {
      case config: api.JsEnvConfig.NodeJs =>
        workerApi.JsEnvConfig.NodeJs(
          executable = config.executable,
          args = config.args,
          env = config.env,
          sourceMap = config.sourceMap
        )
      case config: api.JsEnvConfig.JsDom =>
        workerApi.JsEnvConfig.JsDom(
          executable = config.executable,
          args = config.args,
          env = config.env
        )
      case config: api.JsEnvConfig.ExoegoJsDomNodeJs =>
        workerApi.JsEnvConfig.ExoegoJsDomNodeJs(
          executable = config.executable,
          args = config.args,
          env = config.env
        )
      case config: api.JsEnvConfig.Phantom =>
        workerApi.JsEnvConfig.Phantom(
          executable = config.executable,
          args = config.args,
          env = config.env,
          autoExit = config.autoExit
        )
      case config: api.JsEnvConfig.Selenium =>
        workerApi.JsEnvConfig.Selenium(
          capabilities = config.capabilities match {
            case options: api.JsEnvConfig.Selenium.ChromeOptions =>
              workerApi.JsEnvConfig.Selenium.ChromeOptions(headless = options.headless)
            case options: api.JsEnvConfig.Selenium.FirefoxOptions =>
              workerApi.JsEnvConfig.Selenium.FirefoxOptions(headless = options.headless)
            case _: api.JsEnvConfig.Selenium.SafariOptions =>
              workerApi.JsEnvConfig.Selenium.SafariOptions()
          }
        )
      case config: api.JsEnvConfig.Playwright =>
        val options = config.capabilities match
          case options: api.JsEnvConfig.Playwright.ChromeOptions =>
            workerApi.JsEnvConfig.Playwright.ChromeOptions(
              headless = options.headless,
              showLogs = options.showLogs,
              debug = options.debug,
              launchOptions = options.launchOptions
            )
          case options: api.JsEnvConfig.Playwright.FirefoxOptions =>
            workerApi.JsEnvConfig.Playwright.FirefoxOptions(
              headless = options.headless,
              showLogs = options.showLogs,
              debug = options.debug,
              firefoxUserPrefs = options.firefoxUserPrefs
            )
          case options: api.JsEnvConfig.Playwright.WebkitOptions =>
            workerApi.JsEnvConfig.Playwright.WebkitOptions(
              headless = options.headless,
              showLogs = options.showLogs,
              debug = options.debug,
              launchOptions = options.launchOptions
            )
        workerApi.JsEnvConfig.Playwright(options)
    }
  }

  private def fromWorkerApi(moduleKind: workerApi.ModuleKind): api.ModuleKind = moduleKind match {
    case workerApi.ModuleKind.NoModule => api.ModuleKind.NoModule
    case workerApi.ModuleKind.ESModule => api.ModuleKind.ESModule
    case workerApi.ModuleKind.CommonJSModule => api.ModuleKind.CommonJSModule
  }

  private def fromWorkerApi(report: workerApi.Report): api.Report = {
    api.Report(
      publicModules =
        report.publicModules.map(module =>
          api.Report.Module(
            moduleID = module.moduleID,
            jsFileName = module.jsFileName,
            sourceMapName = module.sourceMapName,
            moduleKind = fromWorkerApi(module.moduleKind)
          )
        ),
      dest = mill.PathRef(os.Path(report.dest))
    )
  }

  private def toWorkerApi(report: api.Report): workerApi.Report = {
    workerApi.Report(
      publicModules = report.publicModules.map(module =>
        workerApi.Report.Module(
          moduleID = module.moduleID,
          jsFileName = module.jsFileName,
          sourceMapName = module.sourceMapName,
          moduleKind = toWorkerApi(module.moduleKind)
        )
      ),
      dest = report.dest.path.toIO
    )
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

  private def toWorkerApi(importMap: api.ESModuleImportMapping): workerApi.ESModuleImportMapping = {
    importMap match {
      case api.ESModuleImportMapping.Prefix(prefix, replacement) =>
        workerApi.ESModuleImportMapping.Prefix(prefix, replacement)
    }
  }

  def link(
      toolsClasspath: Seq[mill.PathRef],
      runClasspath: Seq[mill.PathRef],
      dest: File,
      main: Result[String],
      forceOutJs: Boolean,
      testBridgeInit: Boolean,
      isFullLinkJS: Boolean,
      optimizer: Boolean,
      sourceMap: Boolean,
      moduleKind: api.ModuleKind,
      esFeatures: api.ESFeatures,
      moduleSplitStyle: api.ModuleSplitStyle,
      outputPatterns: api.OutputPatterns,
      minify: Boolean,
      importMap: Seq[api.ESModuleImportMapping],
      experimentalUseWebAssembly: Boolean
  ): Result[api.Report] = {
    withValue(toolsClasspath) { case (_, bridge) =>
      bridge.link(
        runClasspath = runClasspath.iterator.map(_.path.toNIO).toSeq,
        dest = dest,
        main = main.toEither,
        forceOutJs = forceOutJs,
        testBridgeInit = testBridgeInit,
        isFullLinkJS = isFullLinkJS,
        optimizer = optimizer,
        sourceMap = sourceMap,
        moduleKind = toWorkerApi(moduleKind),
        esFeatures = toWorkerApi(esFeatures),
        moduleSplitStyle = toWorkerApi(moduleSplitStyle),
        outputPatterns = toWorkerApi(outputPatterns),
        minify = minify,
        importMap = importMap.map(toWorkerApi),
        experimentalUseWebAssembly = experimentalUseWebAssembly
      ) match {
        case Right(report) => Result.Success(fromWorkerApi(report))
        case Left(message) => Result.Failure(message)
      }
    }
  }

  def run(toolsClasspath: Seq[mill.PathRef], config: api.JsEnvConfig, report: api.Report): Unit = {
    withValue(toolsClasspath) { case (_, bridge) =>
      bridge.run(toWorkerApi(config), toWorkerApi(report))
    }
  }

  def getFramework(
      toolsClasspath: Seq[mill.PathRef],
      config: api.JsEnvConfig,
      frameworkName: String,
      report: api.Report
  ): (() => Unit, sbt.testing.Framework) = {
    withValue(toolsClasspath) { case (_, bridge) =>
      bridge.getFramework(
        toWorkerApi(config),
        frameworkName,
        toWorkerApi(report)
      )
    }
  }

}

@internal
private[scalajslib] object ScalaJSWorkerExternalModule extends mill.api.ExternalModule {

  def scalaJSWorker: Worker[ScalaJSWorker] =
    Task.Worker { new ScalaJSWorker(Task.ctx().jobs) }
  lazy val millDiscover = Discover[this.type]
}
