package mill
package scalajslib
package worker

import java.io.File

import mill.scalajslib.worker.api._
import org.scalajs.core.tools.io.IRFileCache.IRContainer
import org.scalajs.core.tools.io._
import org.scalajs.core.tools.jsdep.ResolvedJSDependency
import org.scalajs.core.tools.linker.{
  ClearableLinker,
  ESFeatures => ScalaJSESFeatures,
  Linker,
  ModuleInitializer,
  Semantics,
  StandardLinker,
  ModuleKind => ScalaJSModuleKind
}
import org.scalajs.core.tools.logging.ScalaConsoleLogger
import org.scalajs.jsenv._
import org.scalajs.testadapter.TestAdapter

import scala.collection.mutable
import scala.ref.SoftReference

class ScalaJSWorkerImpl extends ScalaJSWorkerApi {
  private case class LinkerInput(
      isFullLinkJS: Boolean,
      optimizer: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures
  )
  private object ScalaJSLinker {
    private val cache = mutable.Map.empty[LinkerInput, SoftReference[ClearableLinker]]
    def reuseOrCreate(input: LinkerInput): ClearableLinker = cache.get(input) match {
      case Some(SoftReference(linker)) => linker
      case _ =>
        val newLinker = createLinker(input)
        cache.update(input, SoftReference(newLinker))
        newLinker
    }
    private def createLinker(input: LinkerInput): ClearableLinker = {
      val semantics = input.isFullLinkJS match {
        case true => Semantics.Defaults.optimized
        case false => Semantics.Defaults
      }
      val scalaJSModuleKind = input.moduleKind match {
        case ModuleKind.NoModule => ScalaJSModuleKind.NoModule
        case ModuleKind.CommonJSModule => ScalaJSModuleKind.CommonJSModule
        case ModuleKind.ESModule => ScalaJSModuleKind.ESModule
      }
      val scalaJSESFeatures =
        ScalaJSESFeatures.Default.withUseECMAScript2015(input.esFeatures.esVersion match {
          case ESVersion.ES5_1 => false
          case ESVersion.ES2015 => true
          case v => throw new Exception(
              s"ESVersion $v is not supported with Scala.js < 1.6. Either update Scala.js or use one of ESVersion.ES5_1 or ESVersion.ES2015"
            )
        })

      val useClosure = input.isFullLinkJS && input.moduleKind != ModuleKind.ESModule
      val config = StandardLinker.Config()
        .withOptimizer(input.optimizer)
        .withClosureCompilerIfAvailable(useClosure)
        .withSemantics(semantics)
        .withModuleKind(scalaJSModuleKind)
        .withESFeatures(scalaJSESFeatures)

      new ClearableLinker(() => StandardLinker(config), config.batchMode)
    }
  }

  def link(
      sources: Array[File],
      libraries: Array[File],
      destDir: File,
      main: String,
      forceOutJs: Boolean, // ignored in 0.6
      testBridgeInit: Boolean, // ignored in 0.6
      isFullLinkJS: Boolean,
      optimizer: Boolean,
      sourceMap: Boolean, // ignored in 0.6
      moduleKind: ModuleKind,
      esFeatures: ESFeatures,
      moduleSplitStyle: ModuleSplitStyle // ignored in 0.6
  ): Either[String, Report] = {
    val linker = ScalaJSLinker.reuseOrCreate(LinkerInput(
      isFullLinkJS = isFullLinkJS,
      optimizer = optimizer,
      moduleKind = moduleKind,
      esFeatures = esFeatures
    ))
    val sourceSJSIRs = sources.map(new FileVirtualScalaJSIRFile(_))
    val jars =
      libraries.map(jar => IRContainer.Jar(new FileVirtualBinaryFile(jar) with VirtualJarFile))
    val jarSJSIRs = jars.flatMap(_.jar.sjsirFiles)
    val jsFileName = "out.js"
    val dest = new File(destDir, jsFileName)
    val destFile = AtomicWritableFileVirtualJSFile(dest)
    val logger = new ScalaConsoleLogger
    val initializer = Option(main).map { cls =>
      ModuleInitializer.mainMethodWithArgs(cls, "main")
    }
    try {
      linker.link(sourceSJSIRs ++ jarSJSIRs, initializer.toSeq, destFile, logger)
      Right(Report(
        publicModules = Seq(Report.Module(
          moduleID = "main",
          jsFileName = jsFileName,
          sourceMapName = Some(s"${jsFileName}.map"),
          moduleKind = moduleKind
        )),
        dest = destDir
      ))
    } catch {
      case e: org.scalajs.core.tools.linker.LinkingException =>
        Left(e.getMessage)
    }
  }

  private def getMainModule(report: Report): Report.Module = report.publicModules.collectFirst {
    case module if module.moduleID == "main" => module
  }.getOrElse(throw new Exception("Linking result does not have a module named `main`"))

  private def getLinkedFile(dest: File, module: Report.Module) = new File(dest, module.jsFileName)

  def run(config: JsEnvConfig, report: Report): Unit = {
    jsEnv(config)
      .jsRunner(FileVirtualJSFile(getLinkedFile(report.dest, getMainModule(report))))
      .run(new ScalaConsoleLogger, ConsoleJSConsole)
  }

  def getFramework(
      config: JsEnvConfig,
      frameworkName: String,
      report: Report
  ): (() => Unit, sbt.testing.Framework) = {
    val mainModule = getMainModule(report)
    val moduleKind = mainModule.moduleKind
    val linkedFile = getLinkedFile(report.dest, mainModule)

    val env = jsEnv(config).loadLibs(
      Seq(ResolvedJSDependency.minimal(new FileVirtualJSFile(linkedFile)))
    )

    val moduleIdentifier = Option[String](linkedFile.getAbsolutePath)

    val tconfig = moduleKind match {
      case ModuleKind.NoModule => TestAdapter.Config().withLogger(new ScalaConsoleLogger)
      case ModuleKind.CommonJSModule =>
        TestAdapter.Config().withLogger(new ScalaConsoleLogger).withModuleSettings(
          ScalaJSModuleKind.CommonJSModule,
          moduleIdentifier
        )
      case ModuleKind.ESModule =>
        TestAdapter.Config().withLogger(new ScalaConsoleLogger).withModuleSettings(
          ScalaJSModuleKind.ESModule,
          moduleIdentifier
        )
    }

    val adapter =
      new TestAdapter(env, tconfig)

    (
      () => adapter.close(),
      adapter
        .loadFrameworks(List(List(frameworkName)))
        .flatten
        .headOption
        .getOrElse(throw new RuntimeException("Failed to get framework"))
    )
  }

  def jsEnv(config: JsEnvConfig): ComJSEnv = config match {
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
    case _: JsEnvConfig.ExoegoJsDomNodeJs => throw new Exception(
      "Not supported on Scala.js 0.6"
    )
  }
}
