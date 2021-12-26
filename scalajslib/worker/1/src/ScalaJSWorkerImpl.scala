package mill
package scalajslib
package worker

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import java.io.File
import mill.api.{internal, Result}
import mill.scalajslib.api.{
  JsEnvConfig,
  ModuleInitializer,
  ModuleKind,
  ModuleSplitStyle,
  OutputPatterns
}
import org.scalajs.linker.{PathIRContainer, PathIRFile, PathOutputFile, StandardImpl}
import org.scalajs.linker.interface.{
  ModuleInitializer => ScalaJSModuleInitializer,
  ModuleKind => ScalaJSModuleKind,
  ModuleSplitStyle => ScalaJsModuleSplitStyle,
  _
}
import org.scalajs.logging.ScalaConsoleLogger
import org.scalajs.jsenv.{Input, JSEnv, RunConfig}
import org.scalajs.jsenv.nodejs.NodeJSEnv.SourceMap
import org.scalajs.testing.adapter.TestAdapter
import org.scalajs.testing.adapter.{TestAdapterInitializer => TAI}

import scala.collection.mutable
import scala.ref.WeakReference

@internal
class ScalaJSWorkerImpl extends mill.scalajslib.api.ScalaJSWorkerApi {
  private case class LinkerInput(
      fullOpt: Boolean,
      moduleKind: ModuleKind,
      useECMAScript2015: Boolean,
      dest: File
  )
  private object ScalaJSLinker {
    private val cache = mutable.Map.empty[LinkerInput, WeakReference[Linker]]
    def reuseOrCreate(input: LinkerInput): Linker = cache.get(input) match {
      case Some(WeakReference(linker)) => linker
      case _ =>
        val newLinker = createLinker(input)
        cache.update(input, WeakReference(newLinker))
        newLinker
    }
    private def createLinker(input: LinkerInput): Linker = {
      val semantics = input.fullOpt match {
        case true => Semantics.Defaults.optimized
        case false => Semantics.Defaults
      }
      val scalaJSModuleKind = input.moduleKind match {
        case ModuleKind.NoModule => ScalaJSModuleKind.NoModule
        case ModuleKind.CommonJSModule => ScalaJSModuleKind.CommonJSModule
        case ModuleKind.ESModule => ScalaJSModuleKind.ESModule
      }
      val useClosure = input.fullOpt && input.moduleKind != ModuleKind.ESModule
      val config = StandardConfig()
        .withOptimizer(input.fullOpt)
        .withClosureCompilerIfAvailable(useClosure)
        .withSemantics(semantics)
        .withModuleKind(scalaJSModuleKind)
        .withESFeatures(_.withUseECMAScript2015(input.useECMAScript2015))
      StandardImpl.linker(config)
    }
  }
  def link(
      sources: Array[File],
      libraries: Array[File],
      dest: File,
      main: String,
      testBridgeInit: Boolean,
      fullOpt: Boolean,
      moduleKind: ModuleKind,
      useECMAScript2015: Boolean
  ) = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val linker =
      ScalaJSLinker.reuseOrCreate(LinkerInput(fullOpt, moduleKind, useECMAScript2015, dest))
    val cache = StandardImpl.irFileCache().newCache
    val sourceIRsFuture = Future.sequence(sources.toSeq.map(f => PathIRFile(f.toPath())))
    val irContainersPairs = PathIRContainer.fromClasspath(libraries.map(_.toPath()))
    val libraryIRsFuture = irContainersPairs.flatMap(pair => cache.cached(pair._1))
    val jsFile = dest.toPath()
    val sourceMap = jsFile.resolveSibling(jsFile.getFileName + ".map")
    val linkerOutput = LinkerOutput(PathOutputFile(jsFile))
      .withJSFileURI(java.net.URI.create(jsFile.getFileName.toString))
      .withSourceMap(PathOutputFile(sourceMap))
      .withSourceMapURI(java.net.URI.create(sourceMap.getFileName.toString))
    val logger = new ScalaConsoleLogger
    val mainInitializer = Option(main).map { cls =>
      ScalaJSModuleInitializer.mainMethodWithArgs(cls, "main")
    }
    val testInitializer =
      if (testBridgeInit)
        Some(ScalaJSModuleInitializer.mainMethod(TAI.ModuleClassName, TAI.MainMethodName))
      else None
    val moduleInitializers = mainInitializer.toList ::: testInitializer.toList

    val resultFuture = (for {
      sourceIRs <- sourceIRsFuture
      libraryIRs <- libraryIRsFuture
      _ <- linker.link(sourceIRs ++ libraryIRs, moduleInitializers, linkerOutput, logger)
    } yield {
      Result.Success(dest)
    }).recover {
      case e: org.scalajs.linker.interface.LinkingException =>
        Result.Failure(e.getMessage)
    }

    Await.result(resultFuture, Duration.Inf)
  }

  private lazy val linker13Support = new ScalaJsLinker_1_3Support()

  def linkJs(
      sources: Array[File],
      libraries: Array[File],
      destDirectory: File,
      main: Option[String],
      testBridgeInit: Boolean,
      fullOpt: Boolean,
      moduleKind: ModuleKind,
      moduleSplitStyle: ModuleSplitStyle,
      moduleInitializers: Seq[ModuleInitializer],
      outputPatterns: OutputPatterns,
      useECMAScript2015: Boolean
  ): Result[File] = {
    if (org.scalajs.ir.ScalaJSVersions.current >= "1.3")
      linker13Support.linkJs(
        sources,
        libraries,
        destDirectory,
        main,
        testBridgeInit,
        fullOpt,
        moduleKind,
        moduleSplitStyle,
        moduleInitializers,
        outputPatterns,
        useECMAScript2015
      )
    else
      mill.api.Result.Failure("fastLinkJS/fullLinkJS is not supported in Scala.js below 1.3")
  }

  def run(config: JsEnvConfig, linkedFile: File): Unit = {
    val env = jsEnv(config)
    val input = jsEnvInput(linkedFile)
    val runConfig = RunConfig().withLogger(new ScalaConsoleLogger)
    Run.runInterruptible(env, input, runConfig)
  }

  def getFramework(
      config: JsEnvConfig,
      frameworkName: String,
      linkedFile: File,
      moduleKind: ModuleKind
  ): (() => Unit, sbt.testing.Framework) = {
    val env = jsEnv(config)
    val input = jsEnvInput(linkedFile)
    val tconfig = TestAdapter.Config().withLogger(new ScalaConsoleLogger)

    val adapter = new TestAdapter(env, input, tconfig)

    (
      () => adapter.close(),
      adapter
        .loadFrameworks(List(List(frameworkName)))
        .flatten
        .headOption
        .getOrElse(throw new RuntimeException("Failed to get framework"))
    )
  }

  def jsEnv(config: JsEnvConfig): JSEnv = config match {
    case config: JsEnvConfig.NodeJs =>
      /* In Mill, `config.sourceMap = true` means that `source-map-support`
       * should be used *if available*, as it is what was used to mean in
       * Scala.js 0.6.x. Scala.js 1.x has 3 states: enable, enable-if-available
       * and disable. The former (enable) *fails* if it cannot load the
       * `source-map-support` module. We must therefore adapt the boolean to
       * one of the two last states.
       */
      new org.scalajs.jsenv.nodejs.NodeJSEnv(
        org.scalajs.jsenv.nodejs.NodeJSEnv.Config()
          .withExecutable(config.executable)
          .withArgs(config.args)
          .withEnv(config.env)
          .withSourceMap(if (config.sourceMap) SourceMap.EnableIfAvailable else SourceMap.Disable)
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
      )
  }

  def jsEnvInput(linkedFile: File): Seq[Input] =
    Seq(Input.Script(linkedFile.toPath()))
}
