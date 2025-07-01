package mill.scalalib

import mill.api.{Result, experimental}
import mill.api.{BuildCtx, PathRef}
import mill.api.shared.internal.SemanticDbJavaModuleApi
import mill.constants.CodeGenConstants
import mill.api.ModuleRef
import mill.util.BuildInfo
import mill.jvmlib.api.{CompilationResult, JvmWorkerUtil}
import mill.scalalib.internal.SemanticdbProcessor
import mill.util.Version
import mill.{T, Task}
import mill.api.BuildCtx

import scala.jdk.CollectionConverters.*
import scala.util.Properties
import mill.api.shared.internal.bsp.BspBuildTarget

@experimental
trait SemanticDbJavaModule extends CoursierModule with SemanticDbJavaModuleApi
    with WithJvmWorkerModule {
  def jvmWorker: ModuleRef[JvmWorkerModule]
  def upstreamCompileOutput: T[Seq[CompilationResult]]
  def zincReportCachedProblems: T[Boolean]
  def zincIncrementalCompilation: T[Boolean]
  def allSourceFiles: T[Seq[PathRef]]
  def compile: T[mill.jvmlib.api.CompilationResult]
  private[mill] def bspBuildTarget: BspBuildTarget
  def javacOptions: T[Seq[String]]
  def mandatoryJavacOptions: T[Seq[String]]
  def compileClasspath: T[Seq[PathRef]]

  def semanticDbVersion: T[String] = Task.Input {
    val builtin = SemanticDbJavaModuleApi.buildTimeSemanticDbVersion
    val requested = Task.env.getOrElse[String](
      "SEMANTICDB_VERSION",
      SemanticDbJavaModuleApi.contextSemanticDbVersion.get().getOrElse(builtin)
    )
    Version.chooseNewest(requested, builtin)(Version.IgnoreQualifierOrdering)
  }

  def semanticDbJavaVersion: T[String] = Task.Input {
    val builtin = SemanticDbJavaModuleApi.buildTimeJavaSemanticDbVersion
    val requested = Task.env.getOrElse[String](
      "JAVASEMANTICDB_VERSION",
      SemanticDbJavaModuleApi.contextJavaSemanticDbVersion.get().getOrElse(builtin)
    )
    Version.chooseNewest(requested, builtin)(Version.IgnoreQualifierOrdering)
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

  def semanticDbData: T[PathRef] = Task(persistent = true) {
    val javacOpts = SemanticDbJavaModule.javacOptionsTask(
      javacOptions() ++ mandatoryJavacOptions(),
      semanticDbJavaVersion()
    )

    // we currently assume, we don't do incremental java compilation
    os.remove.all(Task.dest / "classes")

    Task.log.debug(s"effective javac options: ${javacOpts}")

    jvmWorker().worker()
      .compileJava(
        upstreamCompileOutput = upstreamCompileOutput(),
        sources = allSourceFiles().map(_.path),
        compileClasspath =
          (compileClasspath() ++ resolvedSemanticDbJavaPluginMvnDeps()).map(_.path),
        javaHome = javaHome().map(_.path),
        javacOptions = javacOpts,
        reporter = Task.reporter.apply(hashCode),
        reportCachedProblems = zincReportCachedProblems(),
        incrementalCompilation = zincIncrementalCompilation()
      )
      .map { r =>
        BuildCtx.withFilesystemCheckerDisabled {
          SemanticDbJavaModule.copySemanticdbFiles(
            r.classes.path,
            BuildCtx.workspaceRoot,
            Task.dest / "data"
          )
        }
      }
  }

  // keep in sync with bspCompiledClassesAndSemanticDbFiles
  def compiledClassesAndSemanticDbFiles: T[PathRef] = Task {
    val dest = Task.dest
    val classes = compile().classes.path
    val sems = semanticDbData().path
    if (os.exists(sems)) os.copy(sems, dest, mergeFolders = true)
    if (os.exists(classes)) os.copy(classes, dest, mergeFolders = true, replaceExisting = true)
    PathRef(dest)
  }

  // keep in sync with compiledClassesAndSemanticDbFiles
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

object SemanticDbJavaModule {

  def javacOptionsTask(javacOptions: Seq[String], semanticDbJavaVersion: String)(implicit
      ctx: mill.api.TaskCtx
  ): Seq[String] = {
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
      Version.isAtLeast(semanticDbJavaVersion, "0.8.10")(Version.IgnoreQualifierOrdering)
    val buildTool = s" -build-tool:${if (isNewEnough) "mill" else "sbt"}"
    val verbose = if (ctx.log.debugEnabled) " -verbose" else ""
    javacOptions ++ Seq(
      s"-Xplugin:semanticdb -sourceroot:${ctx.workspace} -targetroot:${ctx.dest / "classes"}${buildTool}${verbose}"
    ) ++ extracJavacExports
  }

  private val userCodeStartMarker = "//SOURCECODE_ORIGINAL_CODE_START_MARKER"

  private def postProcessed(
      generatedSourceSemdb: os.Path,
      sourceroot: os.Path,
      semdbRoot: os.Path
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

      val res = SemanticdbProcessor.postProcess(
        os.read(source),
        source.relativeTo(sourceroot),
        adjust = lineIdx => Some(lineIdx - firstLineIdx).filter(_ >= 0),
        generatedSourceSemdb
      )
      val sourceSemdbSubPath = {
        val sourceSubPath = source.relativeTo(sourceroot).asSubPath
        sourceSubPath / os.up / s"${sourceSubPath.last}.semanticdb"
      }
      (res, sourceSemdbSubPath)
    }
  }

  // The semanticdb-javac plugin has issues with the -sourceroot setting, so we correct this on the fly
  def copySemanticdbFiles(
      classesDir: os.Path,
      sourceroot: os.Path,
      targetDir: os.Path
  ): PathRef = {
    assert(classesDir != targetDir)
    os.remove.all(targetDir)
    os.makeDir.all(targetDir)

    val ups = sourceroot.segments.size
    val semanticPath = os.rel / "META-INF/semanticdb"
    val toClean = classesDir / semanticPath / sourceroot.segments.toSeq

    // copy over all found semanticdb-files into the target directory
    // but with corrected directory layout
    if (os.exists(classesDir)) os.walk(classesDir, preOrder = true)
      .filter(os.isFile)
      .foreach { p =>
        if (p.ext == "semanticdb") {
          val target =
            if (ups > 0 && p.startsWith(toClean)) {
              targetDir / semanticPath / p.relativeTo(toClean)
            } else {
              targetDir / p.relativeTo(classesDir)
            }
          os.copy(p, target, createFolders = true)
          for ((data, dest) <- postProcessed(p, sourceroot, classesDir / semanticPath))
            os.write.over(targetDir / semanticPath / dest, data, createFolders = true)
        }
      }
    PathRef(targetDir)
  }
}
