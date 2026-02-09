package mill.scalajslib.worker

import java.io.File
import java.nio.file.Path
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import com.armanbilge.sjsimportmap.ImportMappedIRFile
import mill.constants.InputPumper
import mill.scalajslib.config.ScalaJSConfigWorkerApi
import mill.scalajslib.worker.{api => workerApi}
import mill.scalajslib.worker.jsenv.*
import mill.util.CachedFactory
import org.scalajs.ir.ScalaJSVersions
import org.scalajs.linker.{PathIRContainer, PathOutputDirectory, PathOutputFile, StandardImpl}
import org.scalajs.linker.{interface => sjs}
import org.scalajs.logging.{Level, Logger}
import org.scalajs.jsenv.{Input, JSEnv, RunConfig}
import org.scalajs.testing.adapter.TestAdapter
import org.scalajs.testing.adapter.TestAdapterInitializer as TAI
import mill.scalajslib.config.ScalaJSConfigModule
import mill.scalajslib.worker.api.ScalaJSWorkerApi
import mill.scalajslib.{api => api0}
import mill.api.PathRef
import mill.scalajslib.config.ScalaJSConfig

class ScalaJSWorkerImpl(jobs: Int) extends ScalaJSWorkerApi with ScalaJSConfigWorkerApi {
  private case class LinkerInput(
      isFullLinkJS: Boolean,
      dest: File,
      config: sjs.StandardConfig
  )
  private def minorIsGreaterThanOrEqual(number: Int) = ScalaJSVersions.current match {
    case s"1.$n.$_" if n.toIntOption.exists(_ < number) => false
    case _ => true
  }
  private object ScalaJSLinker
      extends CachedFactory[LinkerInput, (sjs.Linker, sjs.IRFileCache.Cache)] {
    private val irFileCache = StandardImpl.irFileCache()
    override def maxCacheSize: Int = jobs
    override def setup(key: LinkerInput): (sjs.Linker, sjs.IRFileCache.Cache) = createLinker(key)
    override def teardown(key: LinkerInput, value: (sjs.Linker, sjs.IRFileCache.Cache)): Unit = {
      value._2.free()
    }
    private def createLinker(input: LinkerInput): (sjs.Linker, sjs.IRFileCache.Cache) = {
      val linker = StandardImpl.clearableLinker(input.config)
      val irFileCacheCache = irFileCache.newCache
      (linker, irFileCacheCache)
    }
  }
  def createLogger() = new Logger {
    // Console.err needs to be stored at instantiation time so it saves the right threadlocal
    // value so can be used by the Scala.js toolchain's threads without losing logs
    val err = Console.err

    def log(level: Level, message: => String): Unit = {
      err.println(message)
    }
    def trace(t: => Throwable): Unit = {
      t.printStackTrace(err)
    }
  }
  override def rawLink(
      runClasspath: Seq[Path],
      dest: File,
      moduleInitializers: Seq[sjs.ModuleInitializer],
      forceOutJs: Boolean,
      testBridgeInit: Boolean,
      isFullLinkJS: Boolean,
      importMap: Seq[workerApi.ESModuleImportMapping],
      config: sjs.StandardConfig
  ): Either[String, sjs.Report] = ScalaJSLinker.withValue(LinkerInput(
    isFullLinkJS = isFullLinkJS,
    dest = dest,
    config = config
  )) { (linker, irFileCacheCache) =>
    // On Scala.js 1.2- we want to use the legacy mode either way since
    // the new mode is not supported and in tests we always use legacy = false
    val useLegacy = forceOutJs || !minorIsGreaterThanOrEqual(3)
    import scala.concurrent.ExecutionContext.Implicits.global
    val irContainersAndPathsFuture = PathIRContainer.fromClasspath(runClasspath)
    val testInitializer =
      if (testBridgeInit)
        Seq(sjs.ModuleInitializer.mainMethod(TAI.ModuleClassName, TAI.MainMethodName))
      else Nil
    val moduleInitializers0 = moduleInitializers ++ testInitializer
    val logger = createLogger()

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
              case workerApi.ESModuleImportMapping.Prefix(prefix, replacement)
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
          var linkerOutput = sjs.LinkerOutput(PathOutputFile(jsFile))
            .withJSFileURI(java.net.URI.create(jsFile.getFileName.toString))
          val sourceMapNameOpt = Option.when(config.sourceMap)(s"${jsFile.getFileName}.map")
          sourceMapNameOpt.foreach { sourceMapName =>
            val sourceMapFile = jsFile.resolveSibling(sourceMapName)
            linkerOutput = linkerOutput
              .withSourceMap(PathOutputFile(sourceMapFile))
              .withSourceMapURI(java.net.URI.create(sourceMapFile.getFileName.toString))
          }
          linker.link(irFiles, moduleInitializers0, linkerOutput, logger).map { _ =>
            new org.scalajs.linker.interface.unstable.ReportImpl(
              publicModules = Seq(new org.scalajs.linker.interface.unstable.ReportImpl.ModuleImpl(
                moduleID = "main",
                jsFileName = jsFileName,
                sourceMapName = sourceMapNameOpt,
                moduleKind = config.moduleKind
              ))
              // dest = dest
            )
          }
        } else {
          val linkerOutput = PathOutputDirectory(dest.toPath())
          linker.link(
            irFiles,
            moduleInitializers0,
            linkerOutput,
            logger
          )
        }
    } yield {
      Right(report)
    }).recover {
      case e: org.scalajs.linker.interface.LinkingException =>
        Left(e.getMessage)
    }

    Await.result(resultFuture, Duration.Inf)
  }

  override def run0(config: workerApi.JsEnvConfig, inputs: Seq[Input]): Unit = {
    val env = jsEnv(config)
    val logger = createLogger()
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
    Run.runInterruptible(env, inputs, runConfig)
  }

  override def rawGetFramework(
      config: workerApi.JsEnvConfig,
      frameworkName: String,
      inputs: Seq[Input]
  ): (() => Unit, sbt.testing.Framework) = {
    val env = jsEnv(config)
    val logger = createLogger()
    val tconfig = TestAdapter.Config().withLogger(logger)

    val adapter = new TestAdapter(env, inputs, tconfig)

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

  def jsEnv(config: workerApi.JsEnvConfig): JSEnv = config match {
    case config: workerApi.JsEnvConfig.NodeJs =>
      NodeJs(config)
    case config: workerApi.JsEnvConfig.JsDom =>
      JsDom(config)
    case config: workerApi.JsEnvConfig.ExoegoJsDomNodeJs =>
      ExoegoJsDomNodeJs(config)
    case config: workerApi.JsEnvConfig.Phantom =>
      Phantom(config)
    case config: workerApi.JsEnvConfig.Selenium =>
      Selenium(config)
    case config: workerApi.JsEnvConfig.Playwright =>
      Playwright(config)
  }

  override def close(): Unit = ScalaJSLinker.close()

  private def fromSjs(moduleKind: sjs.ModuleKind): workerApi.ModuleKind = moduleKind match {
    case sjs.ModuleKind.NoModule => workerApi.ModuleKind.NoModule
    case sjs.ModuleKind.ESModule => workerApi.ModuleKind.ESModule
    case sjs.ModuleKind.CommonJSModule => workerApi.ModuleKind.CommonJSModule
  }

  private def fromSjs(report: sjs.Report, dest: os.Path): workerApi.Report = {
    workerApi.Report(
      publicModules =
        report.publicModules.map(module =>
          workerApi.Report.Module(
            moduleID = module.moduleID,
            jsFileName = module.jsFileName,
            sourceMapName = module.sourceMapName,
            moduleKind = fromSjs(module.moduleKind)
          )
        ),
      dest = dest.toIO
    )
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
      moduleKind: workerApi.ModuleKind,
      esFeatures: workerApi.ESFeatures,
      moduleSplitStyle: workerApi.ModuleSplitStyle,
      outputPatterns: workerApi.OutputPatterns,
      minify: Boolean,
      importMap: Seq[workerApi.ESModuleImportMapping],
      experimentalUseWebAssembly: Boolean
  ): Either[String, workerApi.Report] = {
    rawLink(
      runClasspath = runClasspath,
      dest = dest,
      moduleInitializers = ScalaJSConfigModule.moduleInitializers(main.toOption, true),
      forceOutJs = forceOutJs,
      testBridgeInit = testBridgeInit,
      isFullLinkJS = isFullLinkJS,
      importMap = importMap,
      config = ScalaJSConfig.config(
        sjsVersion = ScalaJSVersions.current,
        moduleSplitStyle = moduleSplitStyle,
        esFeatures = esFeatures,
        moduleKind = moduleKind,
        scalaJSOptimizer = optimizer,
        scalaJSSourceMap = sourceMap,
        patterns = outputPatterns,
        useWebAssembly = experimentalUseWebAssembly
      )
    ).map { sjsReport =>
      fromSjs(sjsReport, os.Path(dest))
    }
  }

  private def fromWorkerApi(moduleKind: workerApi.ModuleKind): api0.ModuleKind = moduleKind match {
    case workerApi.ModuleKind.NoModule => api0.ModuleKind.NoModule
    case workerApi.ModuleKind.ESModule => api0.ModuleKind.ESModule
    case workerApi.ModuleKind.CommonJSModule => api0.ModuleKind.CommonJSModule
  }

  private def fromWorkerApi(report: workerApi.Report): api0.Report = {
    api0.Report(
      publicModules =
        report.publicModules.map(module =>
          api0.Report.Module(
            moduleID = module.moduleID,
            jsFileName = module.jsFileName,
            sourceMapName = module.sourceMapName,
            moduleKind = fromWorkerApi(module.moduleKind)
          )
        ),
      dest = PathRef(os.Path(report.dest))
    )
  }

  def run(config: workerApi.JsEnvConfig, report: workerApi.Report): Unit =
    run0(config, ScalaJSConfigModule.jsEnvInputs(fromWorkerApi(report)))

  def getFramework(
      config: workerApi.JsEnvConfig,
      frameworkName: String,
      report: workerApi.Report
  ): (() => Unit, sbt.testing.Framework) =
    rawGetFramework(config, frameworkName, ScalaJSConfigModule.jsEnvInputs(fromWorkerApi(report)))
}
