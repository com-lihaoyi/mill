package mill.scalajslib.worker

import com.armanbilge.sjsimportmap.ImportMappedIRFile
import mill.constants.InputPumper
import mill.scalajslib.worker.api.*
import mill.scalajslib.worker.jsenv.*
import org.scalajs.ir.ScalaJSVersions
import org.scalajs.linker.{PathIRContainer, PathOutputDirectory, PathOutputFile, StandardImpl}
import org.scalajs.linker.interface.{
  ESFeatures as ScalaJSESFeatures,
  ESVersion as ScalaJSESVersion,
  ModuleKind as ScalaJSModuleKind,
  OutputPatterns as ScalaJSOutputPatterns,
  ModuleSplitStyle as _,
  Report as _,
  *
}
import org.scalajs.logging.{Level, Logger}
import org.scalajs.jsenv.{Input, JSEnv, RunConfig}
import org.scalajs.testing.adapter.TestAdapter
import org.scalajs.testing.adapter.TestAdapterInitializer as TAI

import java.io.{File, PrintWriter, StringWriter}
import java.nio.file.Path
import scala.collection.mutable
import scala.concurrent.Future
import scala.ref.SoftReference

class ScalaJSWorkerImpl extends ScalaJSWorkerApi {
  private case class LinkerInput(
      isFullLinkJS: Boolean,
      optimizer: Boolean,
      sourceMap: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures,
      moduleSplitStyle: ModuleSplitStyle,
      outputPatterns: OutputPatterns,
      minify: Boolean,
      dest: File,
      experimentalUseWebAssembly: Boolean
  )
  private def minorIsGreaterThanOrEqual(number: Int) = ScalaJSVersions.current match {
    case s"1.$n.$_" if n.toIntOption.exists(_ < number) => false
    case _ => true
  }
  private object ScalaJSLinker {
    private val irFileCache = StandardImpl.irFileCache()
    private val cache = mutable.Map.empty[LinkerInput, SoftReference[(Linker, IRFileCache.Cache)]]
    def reuseOrCreate(input: LinkerInput): (Linker, IRFileCache.Cache) = cache.get(input) match {
      case Some(SoftReference((linker, irFileCacheCache))) => (linker, irFileCacheCache)
      case _ =>
        val newResult = createLinker(input)
        cache.update(input, SoftReference(newResult))
        newResult
    }
    private def createLinker(input: LinkerInput): (Linker, IRFileCache.Cache) = {
      val semantics = input.isFullLinkJS match {
        case true => Semantics.Defaults.optimized
        case false => Semantics.Defaults
      }
      val scalaJSModuleKind = input.moduleKind match {
        case ModuleKind.NoModule => ScalaJSModuleKind.NoModule
        case ModuleKind.CommonJSModule => ScalaJSModuleKind.CommonJSModule
        case ModuleKind.ESModule => ScalaJSModuleKind.ESModule
      }
      @scala.annotation.nowarn("cat=deprecation")
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
        .withSourceMap(input.sourceMap)

      def withModuleSplitStyle_1_3_plus(config: StandardConfig): StandardConfig = {
        config.withModuleSplitStyle(
          input.moduleSplitStyle match {
            case ModuleSplitStyle.FewestModules => ScalaJSModuleSplitStyle.FewestModules()
            case ModuleSplitStyle.SmallestModules => ScalaJSModuleSplitStyle.SmallestModules()
            case v @ ModuleSplitStyle.SmallModulesFor(packages*) =>
              if (minorIsGreaterThanOrEqual(10))
                ScalaJSModuleSplitStyle.SmallModulesFor(packages.toList)
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

      val withModuleSplitStyle =
        if (minorIsGreaterThanOrEqual(3)) withModuleSplitStyle_1_3_plus(partialConfig)
        else withModuleSplitStyle_1_2_minus(partialConfig)

      val withOutputPatterns =
        if (minorIsGreaterThanOrEqual(3))
          withModuleSplitStyle
            .withOutputPatterns(
              ScalaJSOutputPatterns.Defaults
                .withJSFile(input.outputPatterns.jsFile)
                .withJSFileURI(input.outputPatterns.jsFileURI)
                .withModuleName(input.outputPatterns.moduleName)
                .withSourceMapFile(input.outputPatterns.sourceMapFile)
                .withSourceMapURI(input.outputPatterns.sourceMapURI)
            )
        else withModuleSplitStyle

      val withMinify =
        if (minorIsGreaterThanOrEqual(16))
          withOutputPatterns.withMinify(input.minify && input.isFullLinkJS)
        else withOutputPatterns

      val withWasm =
        (minorIsGreaterThanOrEqual(17), input.experimentalUseWebAssembly) match {
          case (_, false) => withMinify
          case (true, true) => withMinify.withExperimentalUseWebAssembly(true)
          case (false, true) =>
            throw new Exception("Emitting wasm is not supported with Scala.js < 1.17")
        }

      val linker = StandardImpl.clearableLinker(withWasm)
      val irFileCacheCache = irFileCache.newCache
      (linker, irFileCacheCache)
    }
  }
  private val logger = new Logger {
    def log(level: Level, message: => String): Unit = {
      System.err.println(message)
    }
    def trace(t: => Throwable): Unit = {
      t.printStackTrace()
    }
  }
  private object queuedLogger {
    private val queue = java.util.concurrent.LinkedBlockingQueue[String]()

    def awaitFutureWhilePrinting[T](future: Future[T]): T = {
      while ({
        val message = queue.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (message == null) {
          !future.isCompleted
        } else {
          System.err.println(message)
          true
        }
      }) ()
      future.value.get.get
    }

    val value = new Logger {
      def log(level: Level, message: => String): Unit = {
        queue.put(message)
      }
      def trace(t: => Throwable): Unit = {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        queue.put(sw.toString)
      }
    }
  }
  def link(
      runClasspath: Seq[Path],
      dest: File,
      main: Either[String, String],
      forceOutJs: Boolean,
      testBridgeInit: Boolean,
      isFullLinkJS: Boolean,
      optimizer: Boolean,
      sourceMap: Boolean,
      moduleKind: ModuleKind,
      esFeatures: ESFeatures,
      moduleSplitStyle: ModuleSplitStyle,
      outputPatterns: OutputPatterns,
      minify: Boolean,
      importMap: Seq[ESModuleImportMapping],
      experimentalUseWebAssembly: Boolean
  ): Either[String, Report] = {
    // On Scala.js 1.2- we want to use the legacy mode either way since
    // the new mode is not supported and in tests we always use legacy = false
    val useLegacy = forceOutJs || !minorIsGreaterThanOrEqual(3)
    import scala.concurrent.ExecutionContext.Implicits.global
    val (linker, irFileCacheCache) = ScalaJSLinker.reuseOrCreate(LinkerInput(
      isFullLinkJS = isFullLinkJS,
      optimizer = optimizer,
      sourceMap = sourceMap,
      moduleKind = moduleKind,
      esFeatures = esFeatures,
      moduleSplitStyle = moduleSplitStyle,
      outputPatterns = outputPatterns,
      minify = minify,
      dest = dest,
      experimentalUseWebAssembly = experimentalUseWebAssembly
    ))
    val irContainersAndPathsFuture = PathIRContainer.fromClasspath(runClasspath)
    val testInitializer =
      if (testBridgeInit)
        ModuleInitializer.mainMethod(TAI.ModuleClassName, TAI.MainMethodName) :: Nil
      else Nil
    val moduleInitializers = main match {
      case Right(main) =>
        ModuleInitializer.mainMethodWithArgs(main, "main") ::
          testInitializer
      case _ =>
        testInitializer
    }

    val resultFuture = (for {
      (irContainers, _) <- irContainersAndPathsFuture
      irFiles0 <- irFileCacheCache.cached(irContainers)
      irFiles = if (importMap.isEmpty) {
        irFiles0
      } else {
        if (!minorIsGreaterThanOrEqual(16)) {
          throw new Exception("scalaJSImportMap is not supported with Scala.js < 1.16.")
        }
        val remapFunction = (rawImport: String) => {
          importMap
            .collectFirst {
              case ESModuleImportMapping.Prefix(prefix, replacement)
                  if rawImport.startsWith(prefix) =>
                s"$replacement${rawImport.stripPrefix(prefix)}"
            }
            .getOrElse(rawImport)
        }
        irFiles0.map { ImportMappedIRFile.fromIRFile(_)(remapFunction) }
      }
      report <-
        if (useLegacy) {
          val jsFileName = "out.js"
          val jsFile = new File(dest, jsFileName).toPath()
          var linkerOutput = LinkerOutput(PathOutputFile(jsFile))
            .withJSFileURI(java.net.URI.create(jsFile.getFileName.toString))
          val sourceMapNameOpt = Option.when(sourceMap)(s"${jsFile.getFileName}.map")
          sourceMapNameOpt.foreach { sourceMapName =>
            val sourceMapFile = jsFile.resolveSibling(sourceMapName)
            linkerOutput = linkerOutput
              .withSourceMap(PathOutputFile(sourceMapFile))
              .withSourceMapURI(java.net.URI.create(sourceMapFile.getFileName.toString))
          }
          linker.link(irFiles, moduleInitializers, linkerOutput, queuedLogger.value).map { _ =>
            Report(
              publicModules = Seq(Report.Module(
                moduleID = "main",
                jsFileName = jsFileName,
                sourceMapName = sourceMapNameOpt,
                moduleKind = moduleKind
              )),
              dest = dest
            )
          }
        } else {
          val linkerOutput = PathOutputDirectory(dest.toPath())
          linker.link(
            irFiles,
            moduleInitializers,
            linkerOutput,
            queuedLogger.value
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

    queuedLogger.awaitFutureWhilePrinting(resultFuture)
  }

  def run(config: JsEnvConfig, report: Report): Unit = {
    val env = jsEnv(config)
    val input = jsEnvInput(report)
    val runConfig0 = RunConfig().withLogger(logger)
    val runConfig =
      if (mill.api.SystemStreams.isOriginal()) runConfig0
      else runConfig0
        .withInheritErr(false)
        .withInheritOut(false)
        .withOnOutputStream {
          case (Some(processOut), Some(processErr)) =>
            val sources = Seq(
              (processOut, System.out, "spawnSubprocess.stdout", false),
              (processErr, System.err, "spawnSubprocess.stderr", false)
            )

            for ((std, dest, name, checkAvailable) <- sources) {
              val t = new Thread(
                new InputPumper(() => std, () => dest, checkAvailable),
                name
              )
              t.setDaemon(true)
              t.start()
            }
          case _ => ???
        }
    Run.runInterruptible(env, input, runConfig)
  }

  def getFramework(
      config: JsEnvConfig,
      frameworkName: String,
      report: Report
  ): (() => Unit, sbt.testing.Framework) = {
    val env = jsEnv(config)
    val input = jsEnvInput(report)
    val tconfig = TestAdapter.Config().withLogger(logger)

    val adapter = new TestAdapter(env, input, tconfig)

    (
      () => adapter.close(),
      adapter
        .loadFrameworks(List(List(frameworkName)))
        .flatten
        .headOption
        .getOrElse(throw new RuntimeException(
          """|Test framework class was not found. Please check that:
             |- the correct Scala.js dependency of the framework is used (like mvn"group::artifact::version", instead of mvn"group::artifact:version" for JVM Scala. Note the extra : before the version.)
             |- there are no typos in the framework class name.
             |- the framework library is on the classpath
             |""".stripMargin
        ))
    )
  }

  def jsEnv(config: JsEnvConfig): JSEnv = config match {
    case config: JsEnvConfig.NodeJs =>
      NodeJs(config)
    case config: JsEnvConfig.JsDom =>
      JsDom(config)
    case config: JsEnvConfig.ExoegoJsDomNodeJs =>
      ExoegoJsDomNodeJs(config)
    case config: JsEnvConfig.Phantom =>
      Phantom(config)
    case config: JsEnvConfig.Selenium =>
      Selenium(config)
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
