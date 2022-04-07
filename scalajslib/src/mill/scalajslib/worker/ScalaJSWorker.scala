package mill.scalajslib.worker

import java.io.File

import mill.scalajslib.api
import mill.scalajslib.worker.{api => workerApi}
import mill.api.{Ctx, internal, Result}
import mill.define.Discover
import mill.{Agg, T}

@internal
private[scalajslib] class ScalaJSWorker extends AutoCloseable {
  private var scalaJSWorkerInstanceCache = Option.empty[(Long, workerApi.ScalaJSWorkerApi)]

  private def bridge(toolsClasspath: Agg[os.Path])(implicit ctx: Ctx.Home) = {
    val classloaderSig =
      toolsClasspath.map(p => p.toString().hashCode + os.mtime(p)).sum
    scalaJSWorkerInstanceCache match {
      case Some((sig, bridge)) if sig == classloaderSig => bridge
      case _ =>
        val cl = mill.api.ClassLoader.create(
          toolsClasspath.map(_.toIO.toURI.toURL).toVector,
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
      case config: api.JsEnvConfig.Phantom =>
        workerApi.JsEnvConfig.Phantom(
          executable = config.executable,
          args = config.args,
          env = config.env,
          autoExit = config.autoExit
        )
    }
  }

  private def fromWorkerApi(moduleKind: workerApi.ModuleKind): api.ModuleKind = moduleKind match {
    case workerApi.ModuleKind.NoModule => api.ModuleKind.NoModule
    case workerApi.ModuleKind.ESModule => api.ModuleKind.ESModule
    case workerApi.ModuleKind.CommonJSModule => api.ModuleKind.CommonJSModule
  }

  private def fromWorkerApi(report: workerApi.Report, dest: os.Path): api.Report = {
    api.Report(publicModules =
      report.publicModules.map(module =>
        api.Report.Module(
          moduleID = module.moduleID,
          jsFile = mill.PathRef(dest / module.jsFileName),
          sourceMapName = module.sourceMapName,
          moduleKind = fromWorkerApi(module.moduleKind)
        )
      )
    )
  }

  private def toWorkerApi(report: api.Report): workerApi.Report = {
    workerApi.Report(publicModules =
      report.publicModules.map(module =>
        workerApi.Report.Module(
          moduleID = module.moduleID,
          jsFileName = module.jsFile.path.last,
          sourceMapName = module.sourceMapName,
          moduleKind = toWorkerApi(module.moduleKind)
        )
      )
    )
  }

  def link(
      toolsClasspath: Agg[os.Path],
      sources: Agg[os.Path],
      libraries: Agg[os.Path],
      dest: File,
      main: Option[String],
      legacy: Boolean,
      testBridgeInit: Boolean,
      fullOpt: Boolean,
      moduleKind: api.ModuleKind,
      esFeatures: api.ESFeatures
  )(implicit ctx: Ctx.Home): Result[api.Report] = {
    bridge(toolsClasspath).link(
      sources.items.map(_.toIO).toArray,
      libraries.items.map(_.toIO).toArray,
      dest,
      main.orNull,
      legacy,
      testBridgeInit,
      fullOpt,
      toWorkerApi(moduleKind),
      toWorkerApi(esFeatures)
    ) match {
      case Right(report) => Result.Success(fromWorkerApi(report, os.Path(dest)))
      case Left(message) => Result.Failure(message)
    }
  }

  private def getDest(report: api.Report) = {
    report.publicModules.collectFirst {
      case module if module.moduleID == "main" => (module.jsFile.path / os.up).toIO
    }.getOrElse {
      throw new Exception("Linking result does not have a module named `main`")
    }
  }

  def run(toolsClasspath: Agg[os.Path], config: api.JsEnvConfig, report: api.Report)(
      implicit ctx: Ctx.Home
  ): Unit = {
    val dest = 
    bridge(toolsClasspath).run(toWorkerApi(config), getDest(report), toWorkerApi(report))
  }

  def getFramework(
      toolsClasspath: Agg[os.Path],
      config: api.JsEnvConfig,
      frameworkName: String,
      report: api.Report
  )(implicit ctx: Ctx.Home): (() => Unit, sbt.testing.Framework) = {
    bridge(toolsClasspath).getFramework(
      toWorkerApi(config),
      frameworkName,
      getDest(report),
      toWorkerApi(report)
    )
  }

  override def close(): Unit = {
    scalaJSWorkerInstanceCache = None
  }
}

@internal
private[scalajslib] object ScalaJSWorkerExternalModule extends mill.define.ExternalModule {

  def scalaJSWorker = T.worker { new ScalaJSWorker() }
  lazy val millDiscover = Discover[this.type]
}
