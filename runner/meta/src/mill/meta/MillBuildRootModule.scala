package mill.meta

import java.nio.file.Path
import mill.api.BuildCtx
import mill.*
import mill.api.Result
import mill.api.daemon.internal.internal
import mill.constants.CodeGenConstants.buildFileExtensions
import mill.constants.OutFiles.*
import mill.api.{Discover, PathRef, Task}
import mill.api.internal.RootModule
import mill.scalalib.{Dep, DepSyntax, Lib, ScalaModule}
import mill.javalib.api.{CompilationResult, Versions}
import mill.util.{BuildInfo, MainRootModule}
import mill.api.daemon.internal.MillScalaParser
import mill.api.JsonFormatters.given
import mill.javalib.api.internal.{JavaCompilerOptions, ZincOp}

import scala.jdk.CollectionConverters.ListHasAsScala

/**
 * Mill module for pre-processing a Mill `build.mill` and related files and then
 * compiling them as a normal [[ScalaModule]]. Parses `build.mill`, walks any
 * `import $file`s, wraps the script files to turn them into valid Scala code
 * and then compiles them with the `mvnDeps` extracted from the `//| mvnDeps`
 * calls within the scripts.
 */
@internal
trait MillBuildRootModule()(using
    rootModuleInfo: RootModule.Info
) extends ScalaModule {
  override def bspDisplayName0: String = rootModuleInfo
    .projectRoot
    .relativeTo(rootModuleInfo.topLevelProjectRoot)
    .segments
    .++(super.bspDisplayName0.split("/"))
    .mkString("/")

  override def moduleDir: os.Path = rootModuleInfo.projectRoot / os.up / millBuild
  private[mill] override def intellijModulePathJava: Path = (moduleDir / os.up).toNIO

  override def scalaVersion: T[String] = BuildInfo.scalaVersion

  val scriptSourcesPaths = BuildCtx.watchValue {
    BuildCtx.withFilesystemCheckerDisabled {
      FileImportGraph
        .walkBuildFiles(rootModuleInfo.projectRoot / os.up, rootModuleInfo.output)
        .sorted // Ensure ordering is deterministic
    }
  }

  /**
   * All script files (that will get wrapped later)
   * @see [[generatedSources]]
   */
  def scriptSources: T[Seq[PathRef]] = Task.Sources(scriptSourcesPaths*)

  def parseBuildFiles: T[FileImportGraph] = Task {
    BuildCtx.withFilesystemCheckerDisabled {
      FileImportGraph.parseBuildFiles(
        rootModuleInfo.topLevelProjectRoot,
        rootModuleInfo.projectRoot / os.up,
        rootModuleInfo.output,
        MillScalaParser.current.value,
        scriptSources().map(_.path)
      )
    }
  }

  def cliImports: T[Seq[String]] = Task.Input {
    val imports = CliImports.value
    if (imports.nonEmpty) {
      Task.log.debug(s"Using cli-provided runtime imports: ${imports.mkString(", ")}")
    }
    imports
  }

  override def mandatoryMvnDeps = Task {
    Seq(
      mvn"com.lihaoyi::mill-libs:${Versions.millVersion}",
      mvn"com.lihaoyi::mill-runner-autooverride-api:${Versions.millVersion}"
    ) ++
      // only include mill-runner for meta-builds
      Option.when(rootModuleInfo.projectRoot / os.up != rootModuleInfo.topLevelProjectRoot) {
        mvn"com.lihaoyi::mill-runner-meta:${Versions.millVersion}"
      }
  }

  override def runMvnDeps = Task {
    val imports = cliImports()
    val ivyImports = imports.collect {
      // compat with older Mill-versions
      case s"ivy:$rest" => rest
      case s"mvn:$rest" => rest
    }
    MillIvy.processMillMvnDepsignature(ivyImports).map(mill.scalalib.Dep.parse) ++
      // Needed at runtime to instantiate a `mill.eval.EvaluatorImpl` in the `build.mill`,
      // classloader but should not be available for users to compile against
      Seq(mvn"com.lihaoyi::mill-core-eval:${Versions.millVersion}")

  }

  override def platformSuffix: T[String] = s"_mill${BuildInfo.millBinPlatform}"

  override def generatedSources: T[Seq[PathRef]] = Task {
    generatedScriptSources().support
  }

  /**
   * Additional script files, we generate, since not all Mill source
   * files (`*.mill` can be fed to the compiler as-is.
   *
   * The `wrapped` files aren't supposed to appear under [[generatedSources]] and [[allSources]],
   * since they are derived from [[sources]] and would confuse any further tooling like IDEs.
   */
  def generatedScriptSources
      : T[(wrapped: Seq[PathRef], support: Seq[PathRef], resources: Seq[PathRef])] = Task {
    val wrapped = Task.dest / "wrapped"
    val support = Task.dest / "support"
    val resources = Task.dest / "resources"

    val parsed = parseBuildFiles()
    if (parsed.errors.nonEmpty) Task.fail(parsed.errors.mkString("\n"))
    else {
      CodeGen.generateWrappedAndSupportSources(
        rootModuleInfo.projectRoot / os.up,
        parsed.seenScripts,
        wrapped,
        support,
        resources,
        rootModuleInfo.topLevelProjectRoot,
        rootModuleInfo.output,
        MillScalaParser.current.value
      )
      (
        wrapped = Seq(PathRef(wrapped)),
        support = Seq(PathRef(support)),
        resources = Seq(PathRef(resources))
      )
    }
  }

  def millBuildRootModuleResult = Task {
    val staticBuildOverrides: Map[String, String] = generatedScriptSources()
      .resources
      .map(_.path)
      .filter(os.exists(_))
      .flatMap { root =>
        os.walk(root)
          .filter(_.last == "build-overrides.json")
          .flatMap { p =>
            upickle.read[Map[String, ujson.Value]](os.read(p)).map { case (k, v) =>
              (p.relativeTo(root).segments.dropRight(1).map(s => s"$s.").mkString + k, v.toString)
            }
          }
      }
      .toMap

    Tuple4(runClasspath(), compile().classes, codeSignatures(), staticBuildOverrides)
  }

  def codeSignatures: T[Map[String, Int]] = Task(persistent = true) {
    os.remove.all(Task.dest / "previous")
    if (os.exists(Task.dest / "current"))
      os.move.over(Task.dest / "current", Task.dest / "previous")
    val debugEnabled = Task.log.debugEnabled
    val codesig = mill.codesig.CodeSig
      .compute(
        classFiles = os.walk(compile().classes.path).filter(_.ext == "class"),
        upstreamClasspath = compileClasspath().toSeq.map(_.path),
        ignoreCall = { (callSiteOpt, calledSig) =>
          // We can ignore all calls to methods that look like tasks when traversing
          // the call graph. We can do this because we assume `def` tasks are pure,
          // and so any changes in their behavior will be picked up by the runtime build
          // graph evaluator without needing to be accounted for in the post-compile
          // bytecode callgraph analysis.
          def isSimpleTask(desc: mill.codesig.JvmModel.Desc) =
            (desc.ret.pretty == classOf[Task.Simple[?]].getName ||
              desc.ret.pretty == classOf[Worker[?]].getName) &&
              desc.args.isEmpty

          // We avoid ignoring method calls that are simple trait forwarders, because
          // we need the trait forwarders calls to be counted in order to wire up the
          // method definition that a task is associated with during evaluation
          // (e.g. `myModuleObject.myTask`) with its implementation that may be defined
          // somewhere else (e.g. `trait MyModuleTrait{ def myTask }`). Only that one
          // step is necessary, after that the runtime build graph invalidation logic can
          // take over
          def isForwarderCallsiteOrLambda =
            callSiteOpt.nonEmpty && {
              val callSiteSig = callSiteOpt.get.sig

              (callSiteSig.name == (calledSig.name + "$") &&
                callSiteSig.static &&
                callSiteSig.desc.args.size == 1)
              || (
                // In Scala 3, lambdas are implemented by private instance methods,
                // not static methods, so they fall through the crack of "isSimpleTask".
                // Here make the assumption that a zero-arg lambda called from a simpleTask,
                // should in fact be tracked. e.g. see `integration.invalidation[codesig-hello]`,
                // where the body of the `def foo` task is a zero-arg lambda i.e. the argument
                // of `Cacher.cachedTask`.
                // To be more precise I think ideally we should capture more information in the signature
                isSimpleTask(callSiteSig.desc) && calledSig.name.contains("$anonfun")
              )
            }

          // We ignore Commands for the same reason as we ignore tasks, and also because
          // their implementations get gathered up all the via the `Discover` macro, but this
          // is primarily for use as external entrypoints and shouldn't really be counted as
          // part of the `millbuild.build#<init>` transitive call graph they would normally
          // be counted as
          def isCommand =
            calledSig.desc.ret.pretty == classOf[Command[?]].getName

          // Skip calls to `millDiscover`. `millDiscover` is bundled as part of `RootModule` for
          // convenience, but it should really never be called by any normal Mill module/task code,
          // and is only used by downstream code in `mill.eval`/`mill.resolve`. Thus although CodeSig's
          // conservative analysis considers potential calls from `build_.package_$#<init>` to
          // `millDiscover()`, we can safely ignore that possibility
          def isMillDiscover =
            calledSig.name == "millDiscover$lzyINIT1" ||
              calledSig.name == "millDiscover" ||
              callSiteOpt.exists(_.sig.name == "millDiscover")

          (isSimpleTask(calledSig.desc) && !isForwarderCallsiteOrLambda) ||
          isCommand ||
          isMillDiscover
        },
        logger = new mill.codesig.Logger(
          Task.dest / "current",
          Option.when(debugEnabled)(Task.dest / "current")
        ),
        prevTransitiveCallGraphHashesOpt = () =>
          Option.when(os.exists(Task.dest / "previous/transitiveCallGraphHashes0.json"))(
            upickle.read[Map[String, Int]](
              os.read.stream(Task.dest / "previous/transitiveCallGraphHashes0.json")
            )
          )
      )

    codesig.transitiveCallGraphHashes
  }

  /**
   * All mill build source files.
   * These files are the inputs but not necessarily the same files we feed to the compiler,
   * since we need to process `.mill` files and generate additional Scala files from it.
   */
  override def sources: T[Seq[PathRef]] = Task {
    scriptSources() ++ super.sources()
  }

  override def allSourceFiles: T[Seq[PathRef]] = Task {
    val allMillSources =
      // the real input-sources
      allSources() ++
        // also sources, but derived from `scriptSources`
        generatedScriptSources().wrapped

    val candidates =
      Lib.findSourceFiles(allMillSources, Seq("scala", "java") ++ buildFileExtensions.asScala.toSeq)

    // We need to unlist those files, which we replaced by generating wrapper scripts
    val filesToExclude = Lib.findSourceFiles(scriptSources(), buildFileExtensions.asScala.toSeq)

    candidates.filterNot(filesToExclude.contains).map(PathRef(_))
  }

  def compileMvnDeps = Seq(
    mvn"com.lihaoyi::sourcecode:${Versions.comLihaoyiSourcecodeVersion}"
  )

  override def scalacPluginMvnDeps: T[Seq[Dep]] = Seq(
    // Somehow these sourcecode exclusions are necessary otherwise the
    // SOURCECODE_ORIGINAL_FILE_PATH comments aren't handled properly
    mvn"com.lihaoyi:::scalac-mill-moduledefs-plugin:${Versions.millModuledefsVersion}"
      .exclude("com.lihaoyi" -> "sourcecode_3"),
    mvn"com.lihaoyi:::mill-runner-autooverride-plugin:${Versions.millVersion}"
      .exclude("com.lihaoyi" -> "sourcecode_3")
  )

  override def scalacOptions: T[Seq[String]] = Task {
    super.scalacOptions() ++
      // This warning comes up for package names with dashes in them like "package build.`foo-bar`",
      // but Mill generally handles these fine, so no need to warn the user
      Seq("-deprecation", "-Wconf:msg=will be encoded on the classpath:silent")
  }

  /** Used in BSP IntelliJ, which can only work with directories */
  def dummySources: Task[Seq[PathRef]] = Task.Sources(Task.dest)

  def millVersion: T[String] = Task.Input { BuildInfo.millVersion }

  override def compile: T[CompilationResult] = Task(persistent = true) {
    val mv = millVersion()

    val prevMillVersionFile = Task.dest / s"mill-version"
    val prevMillVersion = Option(prevMillVersionFile)
      .filter(os.exists)
      .map(os.read(_).trim)
      .getOrElse("?")

    if (prevMillVersion != mv) {
      // Mill version changed, drop all previous incremental state
      // see https://github.com/com-lihaoyi/mill/issues/3874
      Task.log.debug(
        s"Detected Mill version change ${prevMillVersion} -> ${mv}. Dropping previous incremental compilation state"
      )
      os.remove.all(Task.dest)
      os.makeDir(Task.dest)
      os.write(prevMillVersionFile, mv)
    }

    // copied from `ScalaModule`
    val jOpts = JavaCompilerOptions.split(javacOptions() ++ mandatoryJavacOptions())
    val worker = jvmWorker().internalWorker()
    worker.apply(
      ZincOp.CompileMixed(
        upstreamCompileOutput = upstreamCompileOutput(),
        sources = Seq.from(allSourceFiles().map(_.path)),
        compileClasspath = compileClasspath().map(_.path),
        javacOptions = jOpts.compiler,
        scalaVersion = scalaVersion(),
        scalaOrganization = scalaOrganization(),
        scalacOptions = allScalacOptions(),
        compilerClasspath = scalaCompilerClasspath(),
        scalacPluginClasspath = scalacPluginClasspath(),
        incrementalCompilation = zincIncrementalCompilation(),
        auxiliaryClassFileExtensions = zincAuxiliaryClassFileExtensions()
      ),
      javaHome = javaHome().map(_.path),
      javaRuntimeOptions = jOpts.runtime,
      reporter = Task.reporter.apply(hashCode),
      reportCachedProblems = zincReportCachedProblems()
    ).map {
      res =>
        // Perform the line-number updating in a copy of the classfiles, because
        // mangling the original class files messes up zinc incremental compilation
        val transformedClasses = Task.dest / "transformed-classes"
        os.remove.all(transformedClasses)
        os.copy(res.classes.path, transformedClasses)

        MillBuildRootModule.updateLineNumbers(
          transformedClasses,
          generatedScriptSources().wrapped.head.path
        )

        res.copy(classes = PathRef(transformedClasses))
    }
  }

  def millDiscover: Discover
}

object MillBuildRootModule {

  private def updateLineNumbers(classesDir: os.Path, generatedScriptSourcesPath: os.Path) = {
    for (p <- os.walk(classesDir) if p.ext == "class") {
      val rel = p.subRelativeTo(classesDir)
      // Hack to reverse engineer the `.mill` name from the `.class` file name
      val sourceNamePrefixOpt0 = rel.last match {
        case s"${pre}_$_.class" => Some(pre)
        case s"${pre}$$$_.class" => Some(pre)
        case s"${pre}.class" => Some(pre)
        case _ => None
      }

      val sourceNamePrefixOpt = sourceNamePrefixOpt0 match {
        case Some("package") if (rel / os.up) == os.rel / "build_" => Some("build")
        case p => p
      }

      for (prefix <- sourceNamePrefixOpt) {
        val sourceFile = generatedScriptSourcesPath / rel / os.up / s"$prefix.mill"
        if (os.exists(sourceFile)) {

          val lineNumberOffset =
            os.read.lines(sourceFile).indexOf("//SOURCECODE_ORIGINAL_CODE_START_MARKER") + 1
          os.write.over(
            p,
            os
              .read
              .stream(p)
              .readBytesThrough(stream => AsmPositionUpdater.postProcess(-lineNumberOffset, stream))
          )
        }
      }
    }
  }

  class BootstrapModule()(using
      rootModuleInfo: RootModule.Info
  ) extends MainRootModule() with MillBuildRootModule() {
    override lazy val millDiscover = Discover[this.type]
  }

  case class Info(
      projectRoot: os.Path,
      output: os.Path,
      topLevelProjectRoot: os.Path
  )
}
