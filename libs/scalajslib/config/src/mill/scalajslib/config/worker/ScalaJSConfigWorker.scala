package mill.scalajslib.config.worker

import mill.*
import mill.scalajslib.api
import mill.scalajslib.worker.{api => workerApi}
import mill.api.TaskCtx
import mill.api.Result
import mill.api.daemon.internal.internal
import mill.api.Discover
import mill.scalajslib.config.*
import mill.util.CachedFactory
import org.scalajs.jsenv.Input
import org.scalajs.linker.{interface => sjs}

import java.io.File
import java.net.URLClassLoader

@internal
private[scalajslib] class ScalaJSConfigWorker(jobs: Int)
    extends CachedFactory[Seq[mill.PathRef], (URLClassLoader, ScalaJSConfigWorkerApi)] {

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
      .asInstanceOf[ScalaJSConfigWorkerApi]

    (cl, bridge)
  }

  override def teardown(
      key: Seq[PathRef],
      value: (URLClassLoader, ScalaJSConfigWorkerApi)
  ): Unit = {
    val (classloader, workerApi) = value
    workerApi.close()
    classloader.close()
  }

  override def maxCacheSize: Int = jobs

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

  private def toWorkerApi(importMap: api.ESModuleImportMapping): workerApi.ESModuleImportMapping = {
    importMap match {
      case api.ESModuleImportMapping.Prefix(prefix, replacement) =>
        workerApi.ESModuleImportMapping.Prefix(prefix, replacement)
    }
  }

  def rawLink(
      toolsClasspath: Seq[mill.PathRef],
      runClasspath: Seq[mill.PathRef],
      dest: File,
      moduleInitializers: Seq[sjs.ModuleInitializer],
      forceOutJs: Boolean,
      testBridgeInit: Boolean,
      isFullLinkJS: Boolean,
      importMap: Seq[api.ESModuleImportMapping],
      config: sjs.StandardConfig
  ): Result[sjs.Report] = {
    withValue(toolsClasspath) { case (_, bridge) =>
      bridge.rawLink(
        runClasspath = runClasspath.iterator.map(_.path.toNIO).toSeq,
        dest = dest,
        moduleInitializers = moduleInitializers,
        forceOutJs = forceOutJs,
        testBridgeInit = testBridgeInit,
        isFullLinkJS = isFullLinkJS,
        importMap = importMap.map(toWorkerApi),
        config = config
      ) match {
        case Right(report) => Result.Success(report)
        case Left(message) => Result.Failure(message)
      }
    }
  }

  def run0(toolsClasspath: Seq[mill.PathRef], config: api.JsEnvConfig, inputs: Seq[Input]): Unit = {
    withValue(toolsClasspath) { case (_, bridge) =>
      bridge.run0(toWorkerApi(config), inputs)
    }
  }

  def rawGetFramework(
      toolsClasspath: Seq[mill.PathRef],
      config: api.JsEnvConfig,
      frameworkName: String,
      inputs: Seq[Input]
  ): (() => Unit, sbt.testing.Framework) = {
    withValue(toolsClasspath) { case (_, bridge) =>
      bridge.rawGetFramework(
        toWorkerApi(config),
        frameworkName,
        inputs
      )
    }
  }

}

@internal
private[scalajslib] object ScalaJSConfigWorkerExternalModule extends mill.api.ExternalModule {

  def scalaJSWorker: Worker[ScalaJSConfigWorker] =
    Task.Worker { new ScalaJSConfigWorker(Task.ctx().jobs) }
  lazy val millDiscover = Discover[this.type]
}
