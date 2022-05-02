package mill.scalajslib

import java.io.File
import mill.api.{Ctx, Result}
import mill.define.Discover
import mill.scalajslib.api._
import mill.scalajslib.worker.ScalaJSWorkerExternalModule
import mill.{Agg, T}

@deprecated("Use mill.scalajslib.worker.ScalaJSWorker instead", since = "mill 0.10.4")
class ScalaJSWorker(private[scalajslib] val bridgeWorker: worker.ScalaJSWorker) extends AutoCloseable {

  def link(
      toolsClasspath: Agg[os.Path],
      sources: Agg[os.Path],
      libraries: Agg[os.Path],
      dest: File,
      main: Option[String],
      testBridgeInit: Boolean,
      fullOpt: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures
  )(implicit ctx: Ctx.Home): Result[os.Path] = bridgeWorker.link(
    toolsClasspath = toolsClasspath,
    sources = sources,
    libraries = libraries,
    dest = dest,
    main = main,
    legacy = true,
    testBridgeInit = testBridgeInit,
    fullOpt = fullOpt,
    moduleKind = moduleKind,
    esFeatures = esFeatures
  ).map(report => report.publicModules.head.jsFile.path)

  def run(toolsClasspath: Agg[os.Path], config: JsEnvConfig, linkedFile: File)(implicit
      ctx: Ctx.Home
  ): Unit = {}

  def getFramework(
      toolsClasspath: Agg[os.Path],
      config: JsEnvConfig,
      frameworkName: String,
      linkedFile: File,
      moduleKind: ModuleKind
  )(implicit ctx: Ctx.Home): (() => Unit, sbt.testing.Framework) = {
    val linkedFilePath = os.Path(linkedFile)
    val report = Report(
      Seq(Report.Module(
        moduleID = "main",
        jsFile = mill.PathRef(linkedFilePath),
        sourceMapName = Some(linkedFilePath.last),
        moduleKind = moduleKind
      ))
    )
    bridgeWorker.getFramework(
      toolsClasspath = toolsClasspath,
      config = config,
      frameworkName = frameworkName,
      report = report
    )
  }

  override def close(): Unit = {
    // we only delegate, so we should no longer close
  }
}

@deprecated("Use mill.scalajslib.worker.ScalaJSWorkerExternalModule instead", since = "mill 0.10.4")
object ScalaJSWorkerApi extends mill.define.ExternalModule {

  def scalaJSWorker = T.worker {
    T.log.error("mill.scalajslib.ScalaJSWorkerApi is deprecated, use mill.scalajslib.worker.ScalaJSWorkerExternalModule instead")
    // delegate to the successor implementation, it's a singleton
    new ScalaJSWorker(ScalaJSWorkerExternalModule.scalaJSWorker())
  }
  lazy val millDiscover = Discover[this.type]
}
