package mill.javalib

import mill.api.{BuildCtx, Discover, ExternalModule, ModuleRef, PathRef, Result, experimental}
import mill.api.daemon.internal.SemanticDbJavaModuleApi
import mill.constants.CodeGenConstants
import mill.util.BuildInfo
import mill.javalib.api.{CompilationResult, JvmWorkerUtil}
import mill.util.Version
import mill.{T, Task}

import scala.jdk.CollectionConverters.*
import scala.util.Properties
import mill.api.daemon.internal.bsp.BspBuildTarget
import mill.javalib.api.internal.{JavaCompilerOptions, ZincCompileJava}

@experimental
trait SemanticDbJavaModule extends CoursierModule with SemanticDbJavaModuleApi
    with WithJvmWorkerModule {
  def jvmWorker: ModuleRef[JvmWorkerModule]

  /**
   * The upstream compilation output of all this module's upstream modules
   */
  def upstreamCompileOutput: T[Seq[CompilationResult]] = Task {
    Task.traverse(transitiveModuleCompileModuleDeps)(_.compile)()
  }

  def upstreamSemanticDbDatas: Task[Seq[SemanticDbJavaModule.SemanticDbData]] =
    Task.sequence(transitiveModuleCompileModuleDeps.map(_.semanticDbDataDetailed))

  def transitiveModuleCompileModuleDeps: Seq[SemanticDbJavaModule]
  def zincReportCachedProblems: T[Boolean]
  def zincIncrementalCompilation: T[Boolean]
  def allSourceFiles: T[Seq[PathRef]]

  /**
   * Compiles the current module to generate compiled classfiles/bytecode.
   *
   * When you override this, you probably also want/need to override [[JavaModule.bspCompileClassesPath]],
   * as that needs to point to the same compilation output path.
   *
   * Keep the paths in sync with [[JavaModule.bspCompileClassesPath]].
   */
  def compile: Task.Simple[mill.javalib.api.CompilationResult] = Task(persistent = true) {
    println("compile() start")
    // Prepare an empty `compileGeneratedSources` folder for java annotation processors
    // to write generated sources into, that can then be picked up by IDEs like IntelliJ
    val compileGenSources = compileGeneratedSources()
    mill.api.BuildCtx.withFilesystemCheckerDisabled {
      os.remove.all(compileGenSources)
      os.makeDir.all(compileGenSources)
    }

    println("compile() check")
    val compileSemanticDb = semanticDbWillBeNeeded().apply(Task.dest)
    Task.log.info(s"compileSemanticDb: $compileSemanticDb")

    val jOpts = JavaCompilerOptions {
      val opts =
        Seq("-s", compileGeneratedSources().toString) ++ javacOptions() ++ mandatoryJavacOptions()

      if (compileSemanticDb) opts ++ SemanticDbJavaModule.javacOptionsTask(semanticDbJavaVersion())
      else opts
    }

    val compileTo = Task.dest

    Task.log.debug(s"compiling to: $compileTo")
    Task.log.debug(s"effective javac options: ${jOpts.compiler}")
    Task.log.debug(s"effective java runtime options: ${jOpts.runtime}")

    val sources = allSourceFiles().map(_.path)

    val compileClasspathSemanticDbJavaPlugin =
      if (compileSemanticDb) resolvedSemanticDbJavaPluginMvnDeps() else Seq.empty

    val compileJavaOp = ZincCompileJava(
      compileTo = Task.dest,
      upstreamCompileOutput = upstreamCompileOutput(),
      sources = sources,
      compileClasspath =
        (compileClasspath() ++ compileClasspathSemanticDbJavaPlugin).map(_.path),
      javacOptions = jOpts.compiler,
      incrementalCompilation = zincIncrementalCompilation()
    )

    val compileJavaResult = jvmWorker()
      .internalWorker()
      .compileJava(
        compileJavaOp,
        javaHome = javaHome().map(_.path),
        javaRuntimeOptions = jOpts.runtime,
        reporter = Task.reporter.apply(hashCode),
        reportCachedProblems = zincReportCachedProblems()
      )

    compileJavaResult.map { compilationResult =>
      if (compileSemanticDb) SemanticDbJavaModule.enhanceCompilationResultWithSemanticDb(
        compileTo = compileJavaOp.compileTo,
        sources = sources,
        workerClasspath = SemanticDbJavaModule.workerClasspath().map(_.path),
        compilationResult = compilationResult
      )
      else compilationResult
    }
  }

  /**
   * Path to sources generated as part of the `compile` step, eg.  by Java annotation
   * processors which often generate source code alongside classfiles during compilation.
   *
   * Typically these do not need to be compiled again, and are only used by IDEs
   */
  def compileGeneratedSources: T[os.Path]

  /**
   * Returns true if the semanticdb will be needed by the BSP client or any of the other Mill daemons that are using
   * the same `out/` directory.
   */
  private[mill] def semanticDbWillBeNeeded: Task[os.Path => Boolean] = Task.Anon {
    (taskDest: os.Path) =>
      // TODO review: read from the files and document why
//    MillBackgroundWrapper.readPreviousPid()
//    val root = os.list(
//      BuildCtx.workspaceRoot / OutFiles.outFor(OutFolderMode.REGULAR) / "bsp-semanticdb-sessions"
//    ).exists()

      val forced = SemanticDbJavaModule.forceSemanticDbCompilation(taskDest)
      val neededByClient = SemanticDbJavaModuleApi.clientNeedsSemanticDb()

      // TODO review: change to debug
      Task.log.info(s"semanticDbWillBeNeeded: forced=$forced, neededByClient=$neededByClient")

      // TODO review: actually use this
      val _ = forced || neededByClient

      true
  }

  private[mill] def bspBuildTarget: BspBuildTarget
  def javacOptions: T[Seq[String]]
  def mandatoryJavacOptions: T[Seq[String]]
  def compileClasspath: Task[Seq[PathRef]]
  def moduleDeps: Seq[JavaModule]

  def semanticDbVersion: T[String] = Task.Input {
    val builtin = SemanticDbJavaModuleApi.buildTimeSemanticDbVersion
    val requested = Task.env.getOrElse[String](
      "SEMANTICDB_VERSION",
      SemanticDbJavaModuleApi.contextSemanticDbVersion.get().getOrElse(builtin)
    )
    Version.chooseNewest(requested, builtin)(using Version.IgnoreQualifierOrdering)
  }

  def semanticDbJavaVersion: T[String] = Task.Input {
    val builtin = SemanticDbJavaModuleApi.buildTimeJavaSemanticDbVersion
    val requested = Task.env.getOrElse[String](
      "JAVASEMANTICDB_VERSION",
      SemanticDbJavaModuleApi.contextJavaSemanticDbVersion.get().getOrElse(builtin)
    )
    Version.chooseNewest(requested, builtin)(using Version.IgnoreQualifierOrdering)
  }

  def semanticDbScalaVersion: T[String] = BuildInfo.scalaVersion

  protected def semanticDbPluginMvnDeps: T[Seq[Dep]] = Task {
    val sv = semanticDbScalaVersion()
    val semDbVersion = semanticDbVersion()
    if (!JvmWorkerUtil.isScala3(sv) && semDbVersion.isEmpty) {
      val msg =
        """|
           |With Scala 2 you must provide a semanticDbVersion
           |
           |def semanticDbVersion = ???
           |""".stripMargin
      Task.fail(msg)
    } else if (JvmWorkerUtil.isScala3(sv)) {
      Seq.empty[Dep]
    } else {
      Seq(
        mvn"org.scalameta:semanticdb-scalac_${sv}:${semDbVersion}"
      )
    }
  }

  private def semanticDbJavaPluginMvnDeps: T[Seq[Dep]] = Task {
    val sv = semanticDbJavaVersion()
    if (sv.isEmpty) {
      val msg =
        """|
           |You must provide a javaSemanticDbVersion
           |
           |def semanticDbJavaVersion = ???
           |""".stripMargin
      Task.fail(msg)
    } else {
      Seq(
        mvn"com.sourcegraph:semanticdb-javac:${sv}"
      )
    }
  }

  /**
   * Scalac options to activate the compiler plugins.
   */
  protected def semanticDbEnablePluginScalacOptions: T[Seq[String]] = Task {
    val resolvedJars = defaultResolver().classpath(
      semanticDbPluginMvnDeps().map(_.exclude("*" -> "*"))
    )
    resolvedJars.iterator.map(jar => s"-Xplugin:${jar.path}").toSeq
  }

  protected def semanticDbPluginClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(semanticDbPluginMvnDeps())
  }

  protected def resolvedSemanticDbJavaPluginMvnDeps: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(semanticDbJavaPluginMvnDeps())
  }

  def semanticDbDataDetailed: Task[SemanticDbJavaModule.SemanticDbData] = Task.Anon {
    // TODO review: this is an ugly hack
    val compileTo = Task.dest / ".." / "compile.Dest"

    println("set the flag")
    os.write.over(compileTo / SemanticDbJavaModule.forceSemanticDbCompilationFilename, "")
    val compilationResult =
      try compile()
      finally {
        println("reset the flag")
        os.remove(compileTo / SemanticDbJavaModule.forceSemanticDbCompilationFilename)
      }
    // TODO review: how to do this?
//    val compileWithSemanticDb = SemanticDbJavaModule.withForcedSemanticDbCompilation(compile)
//    val compilationResult = compileWithSemanticDb.apply().apply(compileTo)
    val semanticDbData =
      compilationResult.semanticDbFiles.getOrElse(throw IllegalStateException(
        "SemanticDB files were not produced, this is a bug in Mill."
      ))
    SemanticDbJavaModule.SemanticDbData(compilationResult, semanticDbData)
  }

  def semanticDbData: T[PathRef] = Task {
    semanticDbDataDetailed().semanticDbFiles
  }

  /**
   * Task containing the SemanticDB files used by VSCode and other IDEs to provide
   * code insights. This is marked `persistent` so that when compilation fails, we
   * keep the old semanticdb files around so IDEs can continue to analyze the code
   * based on the metadata generated by the last successful compilation
   *
   * Keep the returned path to the compiled classes directory in sync with [[bspCompiledClassesAndSemanticDbFiles]].
   */
  def compiledClassesAndSemanticDbFiles: T[PathRef] = Task(persistent = true) {
    val dest = Task.dest
    // Run the `compiledClassesAndSemanticDbFiles` tasks of all module dependencies
    val _ = Task.sequence(moduleDeps.collect { case m: SemanticDbJavaModule =>
      m.compiledClassesAndSemanticDbFiles
    })()

    os.list(dest).foreach(os.remove.all(_))
    val data = semanticDbData().path
    if (os.exists(data)) os.copy(data, dest, mergeFolders = true)
    PathRef(dest)
  }

  /** Keep the returned path to the compiled classes directory in sync with [[compiledClassesAndSemanticDbFiles]]. */
  override private[mill] def bspCompiledClassesAndSemanticDbFiles: T[UnresolvedPath] = {
    if (
      compiledClassesAndSemanticDbFiles.ctx.enclosing == s"${classOf[SemanticDbJavaModule].getName}#compiledClassesAndSemanticDbFiles"
    ) {
      Task {
        Task.log.debug(
          s"compiledClassesAndSemanticDbFiles target was not overridden, assuming hard-coded classes directory for target ${compiledClassesAndSemanticDbFiles}"
        )
        UnresolvedPath.DestPath(
          os.sub,
          compiledClassesAndSemanticDbFiles.ctx.segments
        )
      }
    } else {
      Task {
        Task.log.debug(
          s"compiledClassesAndSemanticDbFiles target was overridden, need to actually execute compilation to get the compiled classes directory for target ${compiledClassesAndSemanticDbFiles}"
        )
        UnresolvedPath.ResolvedPath(compiledClassesAndSemanticDbFiles().path)
      }
    }
  }

  override private[mill] def bspBuildTargetCompileSemanticDb = Task.Anon {
    compiledClassesAndSemanticDbFiles().path.toNIO
  }
}

object SemanticDbJavaModule extends ExternalModule with CoursierModule {
  case class SemanticDbData(
      compilationResult: CompilationResult,
      semanticDbFiles: PathRef
  ) derives upickle.default.ReadWriter

  private[mill] def workerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(
      Dep.millProjectModule("mill-libs-javalib-scalameta-worker")
    ))
  }

  private val forceSemanticDbCompilationFilename = "forceSemanticDbCompilation"

  private[mill] def forceSemanticDbCompilation(taskDest: os.Path): Boolean =
    os.exists(taskDest / forceSemanticDbCompilationFilename)

  private[mill] def withForcedSemanticDbCompilation[T](
      task: Task[T]
  ): Task[os.Path => T] = Task.Anon { (taskDest: os.Path) =>
//    task.wrap {
//      println("set the flag")
//      os.write.over(taskDest / forceSemanticDbCompilationFilename, "")
//    } { (_, result) =>
//      println("reset the flag")
//      os.remove(taskDest / forceSemanticDbCompilationFilename)
//      result
//    }

    println("set the flag")
    os.write.over(taskDest / forceSemanticDbCompilationFilename, "")
    try task()
    finally {
      println("reset the flag")
      os.remove(taskDest / forceSemanticDbCompilationFilename)
    }
  }

  /**
   * This overload just prepends the given `javacOptions`, so it's kind of pointless, but it's already there, so we
   * have to keep it.
   */
  def javacOptionsTask(javacOptions: Seq[String], semanticDbJavaVersion: String)(implicit
      ctx: mill.api.TaskCtx
  ): Seq[String] = {
    javacOptions ++ javacOptionsTask(semanticDbJavaVersion)
  }

  def javacOptionsTask(semanticDbJavaVersion: String)(using ctx: mill.api.TaskCtx): Seq[String] = {
    // these are only needed for Java 17+
    val extracJavacExports =
      if (Properties.isJavaAtLeast(17)) List(
        "-J--add-exports",
        "-Jjdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "-J--add-exports",
        "-Jjdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "-J--add-exports",
        "-Jjdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
        "-J--add-exports",
        "-Jjdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "-J--add-exports",
        "-Jjdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
      )
      else List.empty

    val isNewEnough =
      Version.isAtLeast(semanticDbJavaVersion, "0.8.10")(using Version.IgnoreQualifierOrdering)
    val buildTool = s" -build-tool:${if (isNewEnough) "mill" else "sbt"}"
    val verbose = if (ctx.log.debugEnabled) " -verbose" else ""
    Seq(
      s"-Xplugin:semanticdb -sourceroot:${ctx.workspace} -targetroot:${ctx.dest / "classes"}${buildTool}${verbose}"
    ) ++ extracJavacExports
  }

  private val userCodeStartMarker = "//SOURCECODE_ORIGINAL_CODE_START_MARKER"

  private def postProcessed(
      generatedSourceSemdb: os.Path,
      sourceroot: os.Path,
      semdbRoot: os.Path,
      workerClasspath: Seq[os.Path]
  ): Option[(Array[Byte], os.SubPath)] = {
    val baseName = generatedSourceSemdb.last.stripSuffix(".semanticdb")
    val isGenerated = CodeGenConstants.buildFileExtensions.asScala
      .exists(ext => baseName.endsWith("." + ext))
    Option.when(isGenerated) {
      val generatedSourceSubPath = {
        val subPath = generatedSourceSemdb.relativeTo(semdbRoot).asSubPath
        subPath / os.up / subPath.last.stripSuffix(".semanticdb")
      }
      val generatedSource = sourceroot / generatedSourceSubPath
      val generatedSourceLines = os.read.lines(generatedSource)
      val source = generatedSourceLines
        .collectFirst { case s"//SOURCECODE_ORIGINAL_FILE_PATH=$rest" => os.Path(rest.trim) }
        .getOrElse {
          sys.error(s"Cannot get original source from generated source $generatedSource")
        }

      val firstLineIdx = generatedSourceLines.indexWhere(_.startsWith(userCodeStartMarker)) + 1

      val res = mill.util.Jvm.withClassLoader(
        workerClasspath,
        parent = getClass.getClassLoader
      ) { cl =>
        val cls = cl.loadClass("mill.javalib.scalameta.worker.SemanticdbProcessor")
        cls.getMethods.find(_.getName == "postProcess")
          .get
          .invoke(
            null,
            os.read(source),
            source.relativeTo(sourceroot),
            (lineIdx: Int) => Some(lineIdx - firstLineIdx).filter(_ >= 0),
            generatedSourceSemdb
          ).asInstanceOf[Array[Byte]]
      }

      val sourceSemdbSubPath = {
        val sourceSubPath = source.relativeTo(sourceroot).asSubPath
        sourceSubPath / os.up / s"${sourceSubPath.last}.semanticdb"
      }
      (res, sourceSemdbSubPath)
    }
  }

  private[mill] def enhanceCompilationResultWithSemanticDb(
      compileTo: os.Path,
      sources: Seq[os.Path],
      workerClasspath: Seq[os.Path],
      compilationResult: CompilationResult
  ) = {
    val semanticDbFiles = BuildCtx.withFilesystemCheckerDisabled {
      copySemanticdbFiles(
        classesDir = compilationResult.classes.path,
        sourceroot = BuildCtx.workspaceRoot,
        targetDir = compileTo / "semanticdb-data",
        workerClasspath = workerClasspath,
        sources = sources
      )
    }

    compilationResult.copy(semanticDbFiles = Some(semanticDbFiles))
  }

  // The semanticdb-javac plugin has issues with the -sourceroot setting, so we correct this on the fly
  private[mill] def copySemanticdbFiles(
      classesDir: os.Path,
      sourceroot: os.Path,
      targetDir: os.Path,
      workerClasspath: Seq[os.Path],
      sources: Seq[os.Path]
  ): PathRef = {
    assert(classesDir != targetDir)
    os.remove.all(targetDir)
    os.makeDir.all(targetDir)

    val semanticPath = os.rel / "META-INF/semanticdb"
    val toClean = classesDir / semanticPath / sourceroot.segments.toSeq

    val existingSources = sources.map(_.subRelativeTo(sourceroot)).toSet

    // copy over all found semanticdb-files into the target directory
    // but with corrected directory layout
    if (os.exists(classesDir)) {
      for (source <- os.walk(classesDir, preOrder = true) if os.isFile(source)) {
        val dest =
          if (source.startsWith(toClean)) targetDir / semanticPath / source.relativeTo(toClean)
          else targetDir / source.relativeTo(classesDir)

        os.copy(source, dest, createFolders = true)

        // SemanticDB plugin doesn't delete old `.semanticdb` files during incremental compilation.
        // So we need to do it ourselves, using the fact that the `.semanticdb` files have the same
        // name and path as the `.java` or `.scala` files they are created from, just with `.semanticdb`
        // appended on the end
        import os./
        dest match {
          case folder / s"$prefix.semanticdb"
              if !existingSources.contains(
                (folder / prefix).subRelativeTo(targetDir / semanticPath)
              ) =>
            os.remove(dest)
          case _ =>
        }

        postProcessed(source, sourceroot, classesDir / semanticPath, workerClasspath)
          .foreach { case (data, dest) =>
            os.write.over(targetDir / semanticPath / dest, data, createFolders = true)
          }
      }
    }

    PathRef(targetDir)
  }

  def copySemanticdbFiles(
      classesDir: os.Path,
      sourceroot: os.Path,
      targetDir: os.Path
  ): PathRef = ??? // bincompat stub

  lazy val millDiscover = Discover[this.type]
}
