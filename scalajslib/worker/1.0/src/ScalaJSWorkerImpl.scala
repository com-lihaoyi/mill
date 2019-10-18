package mill
package scalajslib
package worker

import java.io.File

import mill.api.Result
import mill.scalajslib.api.{JsEnvConfig, ModuleKind}
import org.scalajs.core.tools.io._
import org.scalajs.core.tools.linker.{ModuleInitializer, Semantics, StandardLinker, ModuleKind => ScalaJSModuleKind}
import org.scalajs.core.tools.logging.ScalaConsoleLogger
import org.scalajs.jsenv.ConsoleJSConsole
import org.scalajs.testadapter.TestAdapter

class ScalaJSWorkerImpl extends mill.scalajslib.api.ScalaJSWorkerApi {
  def link(sources: Array[File],
           libraries: Array[File],
           dest: File,
           main: String,
           fullOpt: Boolean,
           moduleKind: ModuleKind) = {
    val semantics = fullOpt match {
        case true => Semantics.Defaults.optimized
        case false => Semantics.Defaults
    }
    val scalaJSModuleKind = moduleKind match {
      case ModuleKind.NoModule => ScalaJSModuleKind.NoModule
      case ModuleKind.CommonJSModule => ScalaJSModuleKind.CommonJSModule
    }
    val config = StandardLinker.Config()
      .withOptimizer(fullOpt)
      .withClosureCompilerIfAvailable(fullOpt)
      .withSemantics(semantics)
      .withModuleKind(scalaJSModuleKind)
    val linker = StandardLinker(config)
    val cache = new IRFileCache().newCache
    val sourceIRs = sources.map(FileVirtualScalaJSIRFile)
    val irContainers = FileScalaJSIRContainer.fromClasspath(libraries)
    val libraryIRs = cache.cached(irContainers)
    val destFile = AtomicWritableFileVirtualJSFile(dest)
    val logger = new ScalaConsoleLogger
    val initializer = Option(main).map { cls => ModuleInitializer.mainMethodWithArgs(cls, "main") }

    try {
      linker.link(sourceIRs ++ libraryIRs, initializer.toSeq, destFile, logger)
      Result.Success(dest)
    }catch {case e: org.scalajs.core.tools.linker.LinkingException =>
      Result.Failure(e.getMessage)
    }
  }

  def run(config: JsEnvConfig, linkedFile: File): Unit = {
    jsEnv(config)
      .jsRunner(Seq(FileVirtualJSFile(linkedFile)))
      .run(new ScalaConsoleLogger, ConsoleJSConsole)
  }

  def getFramework(config: JsEnvConfig,
                   frameworkName: String,
                   linkedFile: File,
                   moduleKind: ModuleKind) : (() => Unit, sbt.testing.Framework) = {
    val env = jsEnv(config)
    val tconfig = TestAdapter.Config().withLogger(new ScalaConsoleLogger)

    val adapter =
      new TestAdapter(env, Seq(FileVirtualJSFile(linkedFile)), tconfig)

    (
      () => adapter.close(),
      adapter
        .loadFrameworks(List(List(frameworkName)))
        .flatten
        .headOption
        .getOrElse(throw new RuntimeException("Failed to get framework"))
    )
  }

  def jsEnv(config: JsEnvConfig): org.scalajs.jsenv.ComJSEnv = config match{
    case config: JsEnvConfig.NodeJs =>
      new org.scalajs.jsenv.nodejs.NodeJSEnv(
        org.scalajs.jsenv.nodejs.NodeJSEnv.Config()
          .withExecutable(config.executable)
          .withArgs(config.args)
          .withEnv(config.env)
          .withSourceMap(config.sourceMap)
      )

    case config: JsEnvConfig.JsDom =>
      new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(
        org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv.Config()
          .withExecutable(config.executable)
          .withArgs(config.args)
          .withEnv(config.env)
      )
    case config: JsEnvConfig.Phantom =>
      new org.scalajs.jsenv.phantomjs.PhantomJSEnv(
        org.scalajs.jsenv.phantomjs.PhantomJSEnv.Config()
          .withExecutable(config.executable)
          .withArgs(config.args)
          .withEnv(config.env)
          .withAutoExit(config.autoExit)
      )
  }
}
