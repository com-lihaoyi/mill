package mill.scalanativelib.worker

import java.io.File

import mill.scalanativelib.worker.api.*
import scala.scalanative.util.Scope
import scala.scalanative.build.{
  Build,
  BuildTarget => ScalaNativeBuildTarget,
  Config,
  Discover,
  GC,
  Logger,
  LTO,
  Mode,
  NativeConfig => ScalaNativeNativeConfig
}
import scala.scalanative.testinterface.adapter.TestAdapter

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.Paths

class ScalaNativeWorkerImpl extends mill.scalanativelib.worker.api.ScalaNativeWorkerApi {
  implicit val scope: Scope = Scope.forever

  private def workspaceRootFrom(baseDir: Path): Path = {
    val abs = baseDir.toAbsolutePath.normalize()
    val idx = (0 until (abs.getNameCount - 1)).find { i =>
      abs.getName(i).toString == "out" && abs.getName(i + 1).toString == "build"
    }
    idx match {
      case Some(i) =>
        val root = Option(abs.getRoot).getOrElse(Paths.get(""))
        (0 until i).foldLeft(root) { (acc, j) =>
          if (acc.toString.isEmpty) abs.getName(j)
          else acc.resolve(abs.getName(j))
        }
      case None =>
        Paths.get(System.getProperty("user.dir")).toAbsolutePath.normalize()
    }
  }

  private def resolvePathLike(path: Path, baseDir: Path): Path = {
    val raw = path.toString
    if (path.isAbsolute) path.toAbsolutePath.normalize()
    else if (raw == "out/mill-workspace") workspaceRootFrom(baseDir)
    else if (raw.startsWith("out/mill-workspace/"))
      workspaceRootFrom(baseDir).resolve(raw.stripPrefix("out/mill-workspace/")).normalize()
    else if (raw == "out/mill-home")
      Paths.get(System.getProperty("user.home")).toAbsolutePath.normalize()
    else if (raw.startsWith("out/mill-home/"))
      Paths.get(System.getProperty("user.home"))
        .resolve(raw.stripPrefix("out/mill-home/"))
        .toAbsolutePath
        .normalize()
    else baseDir.resolve(path).normalize()
  }

  def logger(level: NativeLogLevel): Logger = {
    // Console.err needs to be stored at instantiation time so it saves the right threadlocal
    // value and can be used by the Scala Native toolchain's threads without losing logs
    val err = Console.err
    Logger(
      traceFn =
        msg => if (level.value >= NativeLogLevel.Trace.value) err.println(s"[trace] $msg"),
      debugFn =
        msg => if (level.value >= NativeLogLevel.Debug.value) err.println(s"[debug] $msg"),
      infoFn =
        msg => if (level.value >= NativeLogLevel.Info.value) err.println(s"[info] $msg"),
      warnFn =
        msg => if (level.value >= NativeLogLevel.Warn.value) err.println(s"[warn] $msg"),
      errorFn =
        msg => if (level.value >= NativeLogLevel.Error.value) err.println(s"[error] $msg")
    )
  }

  def discoverClang(): File = Discover.clang().toFile
  def discoverClangPP(): File = Discover.clangpp().toFile
  def discoverCompileOptions(): Seq[String] = Discover.compileOptions()
  def discoverLinkingOptions(): Seq[String] = Discover.linkingOptions()
  def defaultGarbageCollector(): String = GC.default.name

  def config(
      mainClass: Either[String, String],
      classpath: Seq[File],
      nativeWorkdir: File,
      nativeClang: File,
      nativeClangPP: File,
      nativeTarget: Option[String],
      nativeCompileOptions: Seq[String],
      nativeLinkingOptions: Seq[String],
      nativeGC: String,
      nativeLinkStubs: Boolean,
      nativeLTO: String,
      releaseMode: String,
      nativeOptimize: Boolean,
      nativeEmbedResources: Boolean,
      nativeIncrementalCompilation: Boolean,
      nativeDump: Boolean,
      nativeMultithreading: Option[Boolean],
      nativeServiceProviders: Map[String, Seq[String]],
      logLevel: NativeLogLevel,
      buildTarget: BuildTarget,
      sourceLevelDebuggingConfig: SourceLevelDebuggingConfig,
      baseName: String
  ): Either[String, Config] = {
    val nativeConfig =
      ScalaNativeNativeConfig.empty
        .withClang(nativeClang.toPath)
        .withClangPP(nativeClangPP.toPath)
        .withTargetTriple(nativeTarget)
        .withCompileOptions(nativeCompileOptions)
        .withLinkingOptions(nativeLinkingOptions)
        .withGC(GC(nativeGC))
        .withLinkStubs(nativeLinkStubs)
        .withMode(Mode(releaseMode))
        .withOptimize(nativeOptimize)
        .withLTO(LTO(nativeLTO))
        .withDump(nativeDump)
        .withBuildTarget(buildTarget match {
          case BuildTarget.Application => ScalaNativeBuildTarget.application
          case BuildTarget.LibraryDynamic => ScalaNativeBuildTarget.libraryDynamic
          case BuildTarget.LibraryStatic => ScalaNativeBuildTarget.libraryStatic
        })
        .withEmbedResources(nativeEmbedResources)
        .withIncrementalCompilation(nativeIncrementalCompilation)
        .withMultithreading(nativeMultithreading)
        .withServiceProviders(nativeServiceProviders)
        .withBaseName(baseName)
        .withSourceLevelDebuggingConfig(
          _.enabled(sourceLevelDebuggingConfig.enabled)
            .generateFunctionSourcePositions(
              sourceLevelDebuggingConfig.generateFunctionSourcePositions
            )
            .generateLocalVariables(sourceLevelDebuggingConfig.generateLocalVariables)
            .withCustomSourceRoots(sourceLevelDebuggingConfig.customSourceRoots)
        )

    val config = Config.empty
      .withClassPath(classpath.map(_.toPath))
      .withBaseDir(nativeWorkdir.toPath)
      .withCompilerConfig(nativeConfig)
      .withLogger(logger(logLevel))

    if (buildTarget == BuildTarget.Application) {
      mainClass match {
        case Left(error) =>
          Left(error)
        case Right(mainClass) =>
          Right(config.withMainClass(Some(mainClass)))
      }
    } else Right(config)
  }

  def nativeLink(nativeConfig: Object, outDirectory: File): File = {
    val config = nativeConfig.asInstanceOf[Config]
    val baseDir = config.baseDir
    val outputDir = resolvePathLike(outDirectory.toPath(), baseDir)
    val fallbackTarget = outputDir.resolve("out")

    try {
      val resultRaw = Await.result(Build.buildCached(config), Duration.Inf)
      val result = resolvePathLike(resultRaw, baseDir)
      Files.createDirectories(outputDir)
      val target = outputDir.resolve(result.getFileName())

      val resultInOutDirectory =
        if (result.toAbsolutePath.normalize() == target.toAbsolutePath.normalize()) result
        else if (Files.exists(result) && Files.exists(target) && Files.isSameFile(result, target)) {
          target
        } else if (!Files.exists(result) && Files.exists(target)) target
        else {
          Files.move(
            result,
            target,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
          )
        }

      resultInOutDirectory.toFile()
    } catch {
      case _: FileAlreadyExistsException if Files.exists(fallbackTarget) => fallbackTarget.toFile
    }
  }

  def getFramework(
      testBinary: File,
      envVars: Map[String, String],
      logLevel: NativeLogLevel,
      frameworkName: String
  ): (() => Unit, sbt.testing.Framework) = {
    val config = TestAdapter.Config()
      .withBinaryFile(testBinary)
      .withEnvVars(envVars)
      .withLogger(logger(logLevel))

    val adapter = new TestAdapter(config)

    (
      () => adapter.close(),
      adapter
        .loadFrameworks(List(List(frameworkName)))
        .flatten
        .headOption
        .getOrElse(throw new RuntimeException("Failed to get framework"))
    )
  }
}
