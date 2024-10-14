package mill.kotlinlib.js

import mainargs.arg
import mill.api.{PathRef, Result}
import mill.define.{Command, Segment, Task}
import mill.kotlinlib.worker.api.{KotlinWorker, KotlinWorkerTarget}
import mill.kotlinlib.{Dep, DepSyntax, KotlinModule}
import mill.scalalib.Lib
import mill.scalalib.api.CompilationResult
import mill.testrunner.TestResult
import mill.util.Jvm
import mill.{Agg, Args, T}
import upickle.default.{macroRW, ReadWriter => RW}

import java.io.File
import java.util.zip.ZipFile

/**
 * This module is very experimental. Don't use it, it is still under the development, APIs can change.
 */
trait KotlinJSModule extends KotlinModule { outer =>

  // region Kotlin/JS configuration

  /** The kind of JS module generated by the compiler */
  def moduleKind: T[ModuleKind] = ModuleKind.PlainModule

  /** Call main function upon execution. */
  def callMain: T[Boolean] = true

  /** Binary type (if any) to produce. If [[BinaryKind.Executable]] is selected, then .js file(s) will be produced. */
  def kotlinJSBinaryKind: T[Option[BinaryKind]] = Some(BinaryKind.Executable)

  /** Whether to emit a source map. */
  def kotlinJSSourceMap: T[Boolean] = true

  /** Whether to embed sources into source map. */
  def kotlinJSSourceMapEmbedSources: T[SourceMapEmbedSourcesKind] = SourceMapEmbedSourcesKind.Never

  /** ES target to use. List of the supported ones depends on the Kotlin version. If not provided, default is used. */
  def kotlinJSESTarget: T[Option[String]] = None

  /**
   * Add variable and function names that you declared in Kotlin code into the source map. See
   *  [[https://kotlinlang.org/docs/compiler-reference.html#source-map-names-policy-simple-names-fully-qualified-names-no Kotlin docs]] for more details
   */
  def kotlinJSSourceMapNamesPolicy: T[SourceMapNamesPolicy] = SourceMapNamesPolicy.No

  /** Split generated .js per-module. Effective only if [[BinaryKind.Executable]] is selected. */
  def splitPerModule: T[Boolean] = true

  /** Run target for the executable (if [[BinaryKind.Executable]] is set). */
  def kotlinJSRunTarget: T[Option[RunTarget]] = None

  // endregion

  // region parent overrides

  override def allSourceFiles: T[Seq[PathRef]] = Task {
    Lib.findSourceFiles(allSources(), Seq("kt")).map(PathRef(_))
  }

  override def mandatoryIvyDeps: T[Agg[Dep]] = Task {
    Agg(
      ivy"org.jetbrains.kotlin:kotlin-stdlib-js:${kotlinVersion()}"
    )
  }

  override def transitiveCompileClasspath: T[Agg[PathRef]] = Task {
    T.traverse(transitiveModuleCompileModuleDeps)(m =>
      Task.Anon {
        val transitiveModuleArtifactPath =
          (if (m.isInstanceOf[KotlinJSModule]) {
             m.asInstanceOf[KotlinJSModule].createKlib(T.dest, m.compile().classes)
           } else m.compile().classes)
        m.localCompileClasspath() ++ Agg(transitiveModuleArtifactPath)
      }
    )().flatten
  }

  /**
   * Compiles all the sources to the IR representation.
   */
  override def compile: T[CompilationResult] = Task {
    kotlinJsCompile(
      outputMode = OutputMode.KlibDir,
      irClasspath = None,
      allKotlinSourceFiles = allKotlinSourceFiles(),
      librariesClasspath = compileClasspath(),
      callMain = callMain(),
      moduleKind = moduleKind(),
      produceSourceMaps = kotlinJSSourceMap(),
      sourceMapEmbedSourcesKind = kotlinJSSourceMapEmbedSources(),
      sourceMapNamesPolicy = kotlinJSSourceMapNamesPolicy(),
      splitPerModule = splitPerModule(),
      esTarget = kotlinJSESTarget(),
      kotlinVersion = kotlinVersion(),
      destinationRoot = T.dest,
      extraKotlinArgs = kotlincOptions(),
      worker = kotlinWorkerTask()
    )
  }

  override def runLocal(args: Task[Args] = Task.Anon(Args())): Command[Unit] =
    Task.Command { run(args)() }

  override def run(args: Task[Args] = Task.Anon(Args())): Command[Unit] = Task.Command {
    val binaryKind = kotlinJSBinaryKind()
    if (binaryKind.isEmpty || binaryKind.get != BinaryKind.Executable) {
      T.log.error("Run action is only allowed for the executable binary")
    }

    val moduleKind = this.moduleKind()

    val linkResult = linkBinary().classes
    if (
      moduleKind == ModuleKind.NoModule &&
      linkResult.path.toIO.listFiles().count(_.getName.endsWith(".js")) > 1
    ) {
      T.log.info("No module type is selected for the executable, but multiple .js files found in the output folder." +
        " This will probably lead to the dependency resolution failure.")
    }

    kotlinJSRunTarget() match {
      case Some(RunTarget.Node) => {
        val testBinaryPath = (linkResult.path / s"${moduleName()}.${moduleKind.extension}")
          .toIO.getAbsolutePath
        Jvm.runSubprocess(
          commandArgs = Seq(
            "node"
          ) ++ args().value ++ Seq(testBinaryPath),
          envArgs = T.env,
          workingDir = T.dest
        )
      }
      case Some(x) =>
        T.log.error(s"Run target $x is not supported")
      case None =>
        throw new IllegalArgumentException("Executable binary should have a run target selected.")
    }

  }

  override def runMainLocal(
      @arg(positional = true) mainClass: String,
      args: String*
  ): Command[Unit] = Task.Command[Unit] {
    mill.api.Result.Failure("runMain is not supported in Kotlin/JS.")
  }

  override def runMain(@arg(positional = true) mainClass: String, args: String*): Command[Unit] =
    Task.Command[Unit] {
      mill.api.Result.Failure("runMain is not supported in Kotlin/JS.")
    }

  /**
   * The actual Kotlin compile task (used by [[compile]] and [[kotlincHelp]]).
   */
  protected override def kotlinCompileTask(
      extraKotlinArgs: Seq[String] = Seq.empty[String]
  ): Task[CompilationResult] = Task.Anon {
    kotlinJsCompile(
      outputMode = OutputMode.KlibDir,
      allKotlinSourceFiles = allKotlinSourceFiles(),
      irClasspath = None,
      librariesClasspath = compileClasspath(),
      callMain = callMain(),
      moduleKind = moduleKind(),
      produceSourceMaps = kotlinJSSourceMap(),
      sourceMapEmbedSourcesKind = kotlinJSSourceMapEmbedSources(),
      sourceMapNamesPolicy = kotlinJSSourceMapNamesPolicy(),
      splitPerModule = splitPerModule(),
      esTarget = kotlinJSESTarget(),
      kotlinVersion = kotlinVersion(),
      destinationRoot = T.dest,
      extraKotlinArgs = kotlincOptions() ++ extraKotlinArgs,
      worker = kotlinWorkerTask()
    )
  }

  /**
   * Creates final executable.
   */
  def linkBinary: T[CompilationResult] = Task {
    kotlinJsCompile(
      outputMode = binaryKindToOutputMode(kotlinJSBinaryKind()),
      irClasspath = Some(compile().classes),
      allKotlinSourceFiles = Seq.empty,
      librariesClasspath = compileClasspath(),
      callMain = callMain(),
      moduleKind = moduleKind(),
      produceSourceMaps = kotlinJSSourceMap(),
      sourceMapEmbedSourcesKind = kotlinJSSourceMapEmbedSources(),
      sourceMapNamesPolicy = kotlinJSSourceMapNamesPolicy(),
      splitPerModule = splitPerModule(),
      esTarget = kotlinJSESTarget(),
      kotlinVersion = kotlinVersion(),
      destinationRoot = T.dest,
      extraKotlinArgs = kotlincOptions(),
      worker = kotlinWorkerTask()
    )
  }

  // endregion

  // region private

  private def createKlib(destFolder: os.Path, irPathRef: PathRef): PathRef = {
    val outputPath = destFolder / s"${moduleName()}.klib"
    Jvm.createJar(
      outputPath,
      Agg(irPathRef.path),
      mill.api.JarManifest.MillDefault,
      fileFilter = (_, _) => true
    )
    PathRef(outputPath)
  }

  private[kotlinlib] def kotlinJsCompile(
      outputMode: OutputMode,
      allKotlinSourceFiles: Seq[PathRef],
      irClasspath: Option[PathRef],
      librariesClasspath: Agg[PathRef],
      callMain: Boolean,
      moduleKind: ModuleKind,
      produceSourceMaps: Boolean,
      sourceMapEmbedSourcesKind: SourceMapEmbedSourcesKind,
      sourceMapNamesPolicy: SourceMapNamesPolicy,
      splitPerModule: Boolean,
      esTarget: Option[String],
      kotlinVersion: String,
      destinationRoot: os.Path,
      extraKotlinArgs: Seq[String],
      worker: KotlinWorker
  )(implicit ctx: mill.api.Ctx): Result[CompilationResult] = {
    val versionAllowed = kotlinVersion.split("\\.").map(_.toInt) match {
      case Array(1, 8, z) => z >= 20
      case Array(1, y, _) => y >= 9
      case _ => true
    }
    if (!versionAllowed) {
      // have to put this restriction, because for older versions some compiler options either didn't exist or
      // had different names. It is possible to go to the lower version supported with a certain effort.
      ctx.log.error("Minimum supported Kotlin version for JS target is 1.8.20.")
      return Result.Aborted
    }

    // compiler options references:
    // * https://kotlinlang.org/docs/compiler-reference.html#kotlin-js-compiler-options
    // * https://github.com/JetBrains/kotlin/blob/v1.9.25/compiler/cli/cli-common/src/org/jetbrains/kotlin/cli/common/arguments/K2JSCompilerArguments.kt

    val inputFiles = irClasspath match {
      case Some(x) => Seq(s"-Xinclude=${x.path.toIO.getAbsolutePath}")
      case None => allKotlinSourceFiles.map(_.path.toIO.getAbsolutePath)
    }

    val librariesCp = librariesClasspath.map(_.path)
      .filter(os.exists)
      .filter(isKotlinJsLibrary)

    val innerCompilerArgs = Seq.newBuilder[String]
    // classpath
    innerCompilerArgs ++= Seq("-libraries", librariesCp.iterator.mkString(File.pathSeparator))
    innerCompilerArgs ++= Seq("-main", if (callMain) "call" else "noCall")
    innerCompilerArgs += "-meta-info"
    if (moduleKind != ModuleKind.NoModule) {
      innerCompilerArgs ++= Seq(
        "-module-kind",
        moduleKind match {
          case ModuleKind.AMDModule => "amd"
          case ModuleKind.UMDModule => "umd"
          case ModuleKind.PlainModule => "plain"
          case ModuleKind.ESModule => "es"
          case ModuleKind.CommonJSModule => "commonjs"
        }
      )
    }
    // what is the better way to find a module simple name, without root path?
    innerCompilerArgs ++= Seq("-ir-output-name", moduleName())
    if (produceSourceMaps) {
      innerCompilerArgs += "-source-map"
      innerCompilerArgs ++= Seq(
        "-source-map-embed-sources",
        sourceMapEmbedSourcesKind match {
          case SourceMapEmbedSourcesKind.Always => "always"
          case SourceMapEmbedSourcesKind.Never => "never"
          case SourceMapEmbedSourcesKind.Inlining => "inlining"
        }
      )
      innerCompilerArgs ++= Seq(
        "-source-map-names-policy",
        sourceMapNamesPolicy match {
          case SourceMapNamesPolicy.No => "no"
          case SourceMapNamesPolicy.SimpleNames => "simple-names"
          case SourceMapNamesPolicy.FullyQualifiedNames => "fully-qualified-names"
        }
      )
    }
    innerCompilerArgs += "-Xir-only"
    if (splitPerModule) {
      innerCompilerArgs += s"-Xir-per-module"
      innerCompilerArgs += s"-Xir-per-module-output-name=${fullModuleName()}"
    }
    val outputArgs = outputMode match {
      case OutputMode.KlibFile =>
        Seq(
          "-Xir-produce-klib-file",
          "-ir-output-dir",
          (destinationRoot / "libs").toIO.getAbsolutePath
        )
      case OutputMode.KlibDir =>
        Seq(
          "-Xir-produce-klib-dir",
          "-ir-output-dir",
          (destinationRoot / "classes").toIO.getAbsolutePath
        )
      case OutputMode.Js =>
        Seq(
          "-Xir-produce-js",
          "-ir-output-dir",
          (destinationRoot / "binaries").toIO.getAbsolutePath
        )
    }

    innerCompilerArgs ++= outputArgs
    innerCompilerArgs += s"-Xir-module-name=${moduleName()}"
    innerCompilerArgs ++= (esTarget match {
      case Some(x) => Seq("-target", x)
      case None => Seq.empty
    })

    val compilerArgs: Seq[String] = Seq(
      innerCompilerArgs.result(),
      extraKotlinArgs,
      // parameters
      inputFiles
    ).flatten

    val compileDestination = os.Path(outputArgs.last)
    if (irClasspath.isEmpty) {
      T.log.info(
        s"Compiling ${allKotlinSourceFiles.size} Kotlin sources to $compileDestination ..."
      )
    } else {
      T.log.info(s"Linking IR to $compileDestination")
    }
    val workerResult = worker.compile(KotlinWorkerTarget.Js, compilerArgs: _*)

    val analysisFile = T.dest / "kotlin.analysis.dummy"
    if (!os.exists(analysisFile)) {
      os.write(target = analysisFile, data = "", createFolders = true)
    }

    val artifactLocation = outputMode match {
      case OutputMode.KlibFile => compileDestination / s"${moduleName()}.klib"
      case OutputMode.KlibDir => compileDestination
      case OutputMode.Js => compileDestination
    }

    workerResult match {
      case Result.Success(_) =>
        CompilationResult(analysisFile, PathRef(artifactLocation))
      case Result.Failure(reason, _) =>
        Result.Failure(reason, Some(CompilationResult(analysisFile, PathRef(artifactLocation))))
      case e: Result.Exception => e
      case Result.Aborted => Result.Aborted
      case Result.Skipped => Result.Skipped
    }
  }

  private def binaryKindToOutputMode(binaryKind: Option[BinaryKind]): OutputMode =
    binaryKind match {
      // still produce IR classes, but they won't be yet linked
      case None => OutputMode.KlibDir
      case Some(BinaryKind.Library) => OutputMode.KlibFile
      case Some(BinaryKind.Executable) => OutputMode.Js
    }

  // these 2 exist to ignore values added to the display name in case of the cross-modules
  // we already have cross-modules in the paths, so we don't need them here
  private def fullModuleNameSegments() = {
    millModuleSegments.value
      .collect { case label: Segment.Label => label.value } match {
      case Nil => Seq("root")
      case segments => segments
    }
  }

  private def moduleName() = fullModuleNameSegments().last
  private def fullModuleName() = fullModuleNameSegments().mkString("-")

  // **NOTE**: This logic may (and probably is) be incomplete
  private def isKotlinJsLibrary(path: os.Path)(implicit ctx: mill.api.Ctx): Boolean = {
    if (os.isDir(path)) {
      true
    } else if (path.ext == "klib") {
      true
    } else if (path.ext == "jar") {
      try {
        // TODO cache these lookups. May be a big performance penalty.
        val zipFile = new ZipFile(path.toIO)
        zipFile.stream()
          .anyMatch(entry => entry.getName.endsWith(".meta.js") || entry.getName.endsWith(".kjsm"))
      } catch {
        case e: Throwable =>
          T.log.error(s"Couldn't open ${path.toIO.getAbsolutePath} as archive.\n${e.toString}")
          false
      }
    } else {
      T.log.debug(s"${path.toIO.getAbsolutePath} is not a Kotlin/JS library, ignoring it.")
      false
    }
  }

  // endregion

  // region Tests module

  /**
   * Generic trait to run tests for Kotlin/JS which doesn't specify test
   * framework. For the particular implementation see [[KotlinTestPackageTests]] or [[KotestTests]].
   */
  trait KotlinJSTests extends KotlinTests with KotlinJSModule {

    // region private

    // TODO may be optimized if there is a single folder for all modules
    // but may be problematic if modules use different NPM packages versions
    private def nodeModulesDir = Task(persistent = true) {
      PathRef(T.dest)
    }

    // NB: for the packages below it is important to use specific version
    // otherwise with random versions there is a possibility to have conflict
    // between the versions of the shared transitive deps
    private def mochaModule = Task {
      val workingDir = nodeModulesDir().path
      Jvm.runSubprocess(
        commandArgs = Seq("npm", "install", "mocha@10.2.0"),
        envArgs = T.env,
        workingDir = workingDir
      )
      PathRef(workingDir / "node_modules" / "mocha" / "bin" / "mocha.js")
    }

    private def sourceMapSupportModule = Task {
      val workingDir = nodeModulesDir().path
      Jvm.runSubprocess(
        commandArgs = Seq("npm", "install", "source-map-support@0.5.21"),
        envArgs = T.env,
        workingDir = nodeModulesDir().path
      )
      PathRef(workingDir / "node_modules" / "source-map-support" / "register.js")
    }

    // endregion

    override def testFramework = ""

    override def kotlinJSBinaryKind: T[Option[BinaryKind]] = Some(BinaryKind.Executable)

    override def splitPerModule = false

    override def testLocal(args: String*): Command[(String, Seq[TestResult])] =
      Task.Command {
        this.test(args: _*)()
      }

    override protected def testTask(
        args: Task[Seq[String]],
        globSelectors: Task[Seq[String]]
    ): Task[(String, Seq[TestResult])] = Task.Anon {
      // This is a terrible hack, but it works
      run(Task.Anon {
        Args(args() ++ Seq(
          // TODO this is valid only for the NodeJS target. Once browser support is
          //  added, need to have different argument handling
          "--require",
          sourceMapSupportModule().path.toString(),
          mochaModule().path.toString()
        ))
      })()
      ("", Seq.empty[TestResult])
    }

    override def kotlinJSRunTarget: T[Option[RunTarget]] = Some(RunTarget.Node)
  }

  /**
   * Run tests for Kotlin/JS target using `kotlin.test` package.
   */
  trait KotlinTestPackageTests extends KotlinJSTests {
    override def ivyDeps = Agg(
      ivy"org.jetbrains.kotlin:kotlin-test-js:${kotlinVersion()}"
    )
  }

  /**
   * Run tests for Kotlin/JS target using Kotest framework.
   */
  trait KotestTests extends KotlinJSTests {

    def kotestVersion: T[String] = "5.9.1"

    private def kotestProcessor = Task {
      defaultResolver().resolveDeps(
        Agg(
          ivy"io.kotest:kotest-framework-multiplatform-plugin-embeddable-compiler-jvm:${kotestVersion()}"
        )
      ).head
    }

    override def kotlincOptions = super.kotlincOptions() ++ Seq(
      s"-Xplugin=${kotestProcessor().path}"
    )

    override def ivyDeps = Agg(
      ivy"io.kotest:kotest-framework-engine-js:${kotestVersion()}",
      ivy"io.kotest:kotest-assertions-core-js:${kotestVersion()}"
    )
  }

  // endregion
}

sealed trait ModuleKind { def extension: String }

object ModuleKind {
  case object NoModule extends ModuleKind { val extension = "js" }
  implicit val rwNoModule: RW[NoModule.type] = macroRW
  case object UMDModule extends ModuleKind { val extension = "js" }
  implicit val rwUMDModule: RW[UMDModule.type] = macroRW
  case object CommonJSModule extends ModuleKind { val extension = "js" }
  implicit val rwCommonJSModule: RW[CommonJSModule.type] = macroRW
  case object AMDModule extends ModuleKind { val extension = "js" }
  implicit val rwAMDModule: RW[AMDModule.type] = macroRW
  case object ESModule extends ModuleKind { val extension = "mjs" }
  implicit val rwESModule: RW[ESModule.type] = macroRW
  case object PlainModule extends ModuleKind { val extension = "js" }
  implicit val rwPlainModule: RW[PlainModule.type] = macroRW
}

sealed trait SourceMapEmbedSourcesKind
object SourceMapEmbedSourcesKind {
  case object Always extends SourceMapEmbedSourcesKind
  implicit val rwAlways: RW[Always.type] = macroRW
  case object Never extends SourceMapEmbedSourcesKind
  implicit val rwNever: RW[Never.type] = macroRW
  case object Inlining extends SourceMapEmbedSourcesKind
  implicit val rwInlining: RW[Inlining.type] = macroRW
}

sealed trait SourceMapNamesPolicy
object SourceMapNamesPolicy {
  case object SimpleNames extends SourceMapNamesPolicy
  implicit val rwSimpleNames: RW[SimpleNames.type] = macroRW
  case object FullyQualifiedNames extends SourceMapNamesPolicy
  implicit val rwFullyQualifiedNames: RW[FullyQualifiedNames.type] = macroRW
  case object No extends SourceMapNamesPolicy
  implicit val rwNo: RW[No.type] = macroRW
}

sealed trait BinaryKind
object BinaryKind {
  case object Library extends BinaryKind
  implicit val rwLibrary: RW[Library.type] = macroRW
  case object Executable extends BinaryKind
  implicit val rwExecutable: RW[Executable.type] = macroRW
  implicit val rw: RW[BinaryKind] = macroRW
}

sealed trait RunTarget
object RunTarget {
  // TODO rely on the node version installed in the env or fetch a specific one?
  case object Node extends RunTarget
  implicit val rwNode: RW[Node.type] = macroRW
  implicit val rw: RW[RunTarget] = macroRW
}

private[kotlinlib] sealed trait OutputMode
private[kotlinlib] object OutputMode {
  case object Js extends OutputMode
  case object KlibDir extends OutputMode
  case object KlibFile extends OutputMode
}
