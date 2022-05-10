package mill.scalajslib.worker

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import java.io.File
import mill.scalajslib.worker.api._
import org.scalajs.ir.ScalaJSVersions
import org.scalajs.linker.{
  PathIRContainer,
  PathIRFile,
  PathOutputDirectory,
  PathOutputFile,
  StandardImpl
}
import org.scalajs.linker.interface.{
  ESFeatures => ScalaJSESFeatures,
  ESVersion => ScalaJSESVersion,
  ModuleKind => ScalaJSModuleKind,
  Report => ScalaJSReport,
  ModuleSplitStyle => _,
  _
}
import org.scalajs.logging.ScalaConsoleLogger
import org.scalajs.jsenv.{Input, JSEnv, RunConfig}
import org.scalajs.jsenv.nodejs.NodeJSEnv.SourceMap
import org.scalajs.testing.adapter.TestAdapter
import org.scalajs.testing.adapter.{TestAdapterInitializer => TAI}

import scala.collection.mutable
import scala.ref.WeakReference

class ScalaJSWorkerImpl extends ScalaJSWorkerApi {
  private case class LinkerInput(
      isFullLinkJS: Boolean,
      optimizer: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures,
      moduleSplitStyle: ModuleSplitStyle,
      dest: File
  )
  private def minorIsGreaterThanOrEqual(number: Int) = ScalaJSVersions.current match {
    case s"1.$n.$_" if n.toIntOption.exists(_ < number) => false
    case _ => true
  }
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
      val semantics = input.isFullLinkJS match {
        case true => Semantics.Defaults.optimized
        case false => Semantics.Defaults
      }
      val scalaJSModuleKind = input.moduleKind match {
        case ModuleKind.NoModule => ScalaJSModuleKind.NoModule
        case ModuleKind.CommonJSModule => ScalaJSModuleKind.CommonJSModule
        case ModuleKind.ESModule => ScalaJSModuleKind.ESModule
      }
      def withESVersion_1_5_minus(esFeatures: ScalaJSESFeatures): ScalaJSESFeatures = {
        val useECMAScript2015: Boolean = input.esFeatures.esVersion match {
          case ESVersion.ES5_1 => false
          case ESVersion.ES2015 => true
          case v => throw new Exception(
              s"ESVersion $v is not supported with Scala.js < 1.6. Either update Scala.js or use one of ESVersion.ES5_1 or ESVersion.ES2015"
            )
        }
        esFeatures.withUseECMAScript2015(useECMAScript2015)
      }
      def withESVersion_1_6_plus(esFeatures: ScalaJSESFeatures): ScalaJSESFeatures = {
        val scalaJSESVersion: ScalaJSESVersion = input.esFeatures.esVersion match {
          case ESVersion.ES5_1 => ScalaJSESVersion.ES5_1
          case ESVersion.ES2015 => ScalaJSESVersion.ES2015
          case ESVersion.ES2016 => ScalaJSESVersion.ES2016
          case ESVersion.ES2017 => ScalaJSESVersion.ES2017
          case ESVersion.ES2018 => ScalaJSESVersion.ES2018
          case ESVersion.ES2019 => ScalaJSESVersion.ES2019
          case ESVersion.ES2020 => ScalaJSESVersion.ES2020
          case ESVersion.ES2021 => ScalaJSESVersion.ES2021
        }
        esFeatures.withESVersion(scalaJSESVersion)
      }
      var scalaJSESFeatures: ScalaJSESFeatures = ScalaJSESFeatures.Defaults
        .withAllowBigIntsForLongs(input.esFeatures.allowBigIntsForLongs)

      if (minorIsGreaterThanOrEqual(4)) {
        scalaJSESFeatures = scalaJSESFeatures
          .withAvoidClasses(input.esFeatures.avoidClasses)
          .withAvoidLetsAndConsts(input.esFeatures.avoidLetsAndConsts)
      }
      scalaJSESFeatures =
        if (minorIsGreaterThanOrEqual(6)) withESVersion_1_6_plus(scalaJSESFeatures)
        else withESVersion_1_5_minus(scalaJSESFeatures)

      val useClosure = input.isFullLinkJS && input.moduleKind != ModuleKind.ESModule
      val partialConfig = StandardConfig()
        .withOptimizer(input.optimizer)
        .withClosureCompilerIfAvailable(useClosure)
        .withSemantics(semantics)
        .withModuleKind(scalaJSModuleKind)
        .withESFeatures(scalaJSESFeatures)

      // Separating ModuleSplitStyle in a standalone object avoids
      // early classloading which fails in Scala.js versions where
      // the classes don't exist
      object ScalaJSModuleSplitStyle {
        import org.scalajs.linker.interface.ModuleSplitStyle
        object SmallModulesFor {
          def apply(packages: List[String]): ModuleSplitStyle =
            ModuleSplitStyle.SmallModulesFor(packages)
        }
        object FewestModules {
          def apply(): ModuleSplitStyle = ModuleSplitStyle.FewestModules
        }
        object SmallestModules {
          def apply(): ModuleSplitStyle = ModuleSplitStyle.SmallestModules
        }
      }

      def withModuleSplitStyle_1_3_plus(config: StandardConfig): StandardConfig = {
        config.withModuleSplitStyle(
          input.moduleSplitStyle match {
            case ModuleSplitStyle.FewestModules => ScalaJSModuleSplitStyle.FewestModules()
            case ModuleSplitStyle.SmallestModules => ScalaJSModuleSplitStyle.SmallestModules()
            case v @ ModuleSplitStyle.SmallModulesFor(packages) =>
              if (minorIsGreaterThanOrEqual(10)) ScalaJSModuleSplitStyle.SmallModulesFor(packages)
              else throw new Exception(
                s"ModuleSplitStyle $v is not supported with Scala.js < 1.10. Either update Scala.js or use one of ModuleSplitStyle.SmallestModules or ModuleSplitStyle.FewestModules"
              )
          }
        )
      }

      def withModuleSplitStyle_1_2_minus(config: StandardConfig): StandardConfig = {
        input.moduleSplitStyle match {
          case ModuleSplitStyle.FewestModules =>
          case v => throw new Exception(
              s"ModuleSplitStyle $v is not supported with Scala.js < 1.2. Either update Scala.js or use ModuleSplitStyle.FewestModules"
            )
        }
        config
      }

      val config =
        if (minorIsGreaterThanOrEqual(3)) withModuleSplitStyle_1_3_plus(partialConfig)
        else withModuleSplitStyle_1_2_minus(partialConfig)

      StandardImpl.linker(config)
    }
  }
  def link(
      sources: Array[File],
      libraries: Array[File],
      dest: File,
      main: String,
      forceOutJs: Boolean,
      testBridgeInit: Boolean,
      isFullLinkJS: Boolean,
      optimizer: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures,
      moduleSplitStyle: ModuleSplitStyle
  ): Either[String, Report] = {
    // On Scala.js 1.2- we want to use the legacy mode either way since
    // the new mode is not supported and in tests we always use legacy = false
    val useLegacy = forceOutJs || !minorIsGreaterThanOrEqual(3)
    import scala.concurrent.ExecutionContext.Implicits.global
    val linker = ScalaJSLinker.reuseOrCreate(LinkerInput(
      isFullLinkJS = isFullLinkJS,
      optimizer = optimizer,
      moduleKind = moduleKind,
      esFeatures = esFeatures,
      moduleSplitStyle = moduleSplitStyle,
      dest = dest
    ))
    val cache = StandardImpl.irFileCache().newCache
    val sourceIRsFuture = Future.sequence(sources.toSeq.map(f => PathIRFile(f.toPath())))
    val irContainersPairs = PathIRContainer.fromClasspath(libraries.map(_.toPath()))
    val libraryIRsFuture = irContainersPairs.flatMap(pair => cache.cached(pair._1))
    val logger = new ScalaConsoleLogger
    val mainInitializer = Option(main).map { cls =>
      ModuleInitializer.mainMethodWithArgs(cls, "main")
    }
    val testInitializer =
      if (testBridgeInit)
        Some(ModuleInitializer.mainMethod(TAI.ModuleClassName, TAI.MainMethodName))
      else None
    val moduleInitializers = mainInitializer.toList ::: testInitializer.toList

    val resultFuture = (for {
      sourceIRs <- sourceIRsFuture
      libraryIRs <- libraryIRsFuture
      report <-
        if (useLegacy) {
          val jsFileName = "out.js"
          val jsFile = new File(dest, jsFileName).toPath()
          val sourceMapName = jsFile.getFileName + ".map"
          val sourceMap = jsFile.resolveSibling(sourceMapName)
          val linkerOutput = LinkerOutput(PathOutputFile(jsFile))
            .withJSFileURI(java.net.URI.create(jsFile.getFileName.toString))
            .withSourceMap(PathOutputFile(sourceMap))
            .withSourceMapURI(java.net.URI.create(sourceMap.getFileName.toString))
          linker.link(sourceIRs ++ libraryIRs, moduleInitializers, linkerOutput, logger).map {
            file =>
              Report(
                publicModules = Seq(Report.Module(
                  moduleID = "main",
                  jsFileName = jsFileName,
                  sourceMapName = Some(sourceMapName),
                  moduleKind = moduleKind
                )),
                dest = dest
              )
          }
        } else {
          val linkerOutput = PathOutputDirectory(dest.toPath())
          linker.link(
            sourceIRs ++ libraryIRs,
            moduleInitializers,
            linkerOutput,
            logger
          ).map { report =>
            Report(
              publicModules =
                report.publicModules.map(module =>
                  Report.Module(
                    moduleID = module.moduleID,
                    jsFileName = module.jsFileName,
                    sourceMapName = module.sourceMapName,
                    // currently moduleKind is always the input moduleKind
                    moduleKind = moduleKind
                  )
                ),
              dest = dest
            )
          }
        }
    } yield {
      Right(report)
    }).recover {
      case e: org.scalajs.linker.interface.LinkingException =>
        Left(e.getMessage)
    }

    Await.result(resultFuture, Duration.Inf)
  }

  def run(config: JsEnvConfig, report: Report): Unit = {
    val env = jsEnv(config)
    val input = jsEnvInput(report)
    val runConfig = RunConfig().withLogger(new ScalaConsoleLogger)
    Run.runInterruptible(env, input, runConfig)
  }

  def getFramework(
      config: JsEnvConfig,
      frameworkName: String,
      report: Report
  ): (() => Unit, sbt.testing.Framework) = {
    val env = jsEnv(config)
    val input = jsEnvInput(report)
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

  def jsEnvInput(report: Report): Seq[Input] = {
    val mainModule = report.publicModules.find(_.moduleID == "main").getOrElse(throw new Exception(
      "Cannot determine `jsEnvInput`: Linking result does not have a " +
        "module named `main`.\n" +
        s"Full report:\n$report"
    ))
    val path = new File(report.dest, mainModule.jsFileName).toPath
    val input = mainModule.moduleKind match {
      case ModuleKind.NoModule => Input.Script(path)
      case ModuleKind.ESModule => Input.ESModule(path)
      case ModuleKind.CommonJSModule => Input.CommonJSModule(path)
    }
    Seq(input)
  }
}
