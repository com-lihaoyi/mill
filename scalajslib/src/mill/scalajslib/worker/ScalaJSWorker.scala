package mill.scalajslib.worker

import java.io.File
import mill.scalajslib.api
import mill.scalajslib.worker.{api => workerApi}
import mill.api.{Ctx, Result, internal}
import mill.define.{Discover, Worker}
import mill.{Agg, T}

@internal
private[scalajslib] class ScalaJSWorker extends AutoCloseable {
  private var scalaJSWorkerInstanceCache = Option.empty[(Long, workerApi.ScalaJSWorkerApi)]

  private def bridge(toolsClasspath: Agg[mill.PathRef])(implicit ctx: Ctx.Home) = {
    val classloaderSig = toolsClasspath.hashCode
    scalaJSWorkerInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val cl = mill.api.ClassLoader.create(
          toolsClasspath.map(_.path.toIO.toURI.toURL).toVector,
          getClass.getClassLoader
        )
        val bridge = cl
          .loadClass("mill.scalajslib.worker.ScalaJSWorkerImpl")
          .getDeclaredConstructor()
          .newInstance()
          .asInstanceOf[workerApi.ScalaJSWorkerApi]
        scalaJSWorkerInstanceCache = Some((classloaderSig, bridge))
        bridge
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
      case api.ModuleSplitStyle.SmallModulesFor(packages) =>
        workerApi.ModuleSplitStyle.SmallModulesFor(packages)
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
            case options: api.JsEnvConfig.Selenium.SafariOptions =>
              workerApi.JsEnvConfig.Selenium.SafariOptions()
          }
        )
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

  def link(
      toolsClasspath: Agg[mill.PathRef],
      runClasspath: Agg[mill.PathRef],
      dest: File,
      main: Either[String, String],
      forceOutJs: Boolean,
      testBridgeInit: Boolean,
      isFullLinkJS: Boolean,
      optimizer: Boolean,
      sourceMap: Boolean,
      moduleKind: api.ModuleKind,
      esFeatures: api.ESFeatures,
      moduleSplitStyle: api.ModuleSplitStyle,
      outputPatterns: api.OutputPatterns
  )(implicit ctx: Ctx.Home): Result[api.Report] = {
    bridge(toolsClasspath).link(
      runClasspath = runClasspath.iterator.map(_.path.toNIO).toSeq,
      dest = dest,
      main = main,
      forceOutJs = forceOutJs,
      testBridgeInit = testBridgeInit,
      isFullLinkJS = isFullLinkJS,
      optimizer = optimizer,
      sourceMap = sourceMap,
      moduleKind = toWorkerApi(moduleKind),
      esFeatures = toWorkerApi(esFeatures),
      moduleSplitStyle = toWorkerApi(moduleSplitStyle),
      outputPatterns = toWorkerApi(outputPatterns)
    ) match {
      case Right(report) => Result.Success(fromWorkerApi(report))
      case Left(message) => Result.Failure(message)
    }
  }

  def run(toolsClasspath: Agg[mill.PathRef], config: api.JsEnvConfig, report: api.Report)(
      implicit ctx: Ctx.Home
  ): Unit = {
    bridge(toolsClasspath).run(toWorkerApi(config), toWorkerApi(report))
  }

  def getFramework(
      toolsClasspath: Agg[mill.PathRef],
      config: api.JsEnvConfig,
      frameworkName: String,
      report: api.Report
  )(implicit ctx: Ctx.Home): (() => Unit, sbt.testing.Framework) = {
    bridge(toolsClasspath).getFramework(
      toWorkerApi(config),
      frameworkName,
      toWorkerApi(report)
    )
  }

  override def close(): Unit = {
    scalaJSWorkerInstanceCache = None
  }
}

@internal
private[scalajslib] object ScalaJSWorkerExternalModule extends mill.define.ExternalModule {

  def scalaJSWorker: Worker[ScalaJSWorker] = T.worker { new ScalaJSWorker() }
  lazy val millDiscover = Discover[this.type]
}
