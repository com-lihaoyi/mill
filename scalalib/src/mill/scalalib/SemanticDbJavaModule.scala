package mill.scalalib

import mill.api.{PathRef, Result, experimental}
import mill.runner.api.SemanticDbJavaModuleApi
import mill.define.ModuleRef
import mill.util.BuildInfo
import mill.scalalib.api.{CompilationResult, Versions, JvmWorkerUtil}
import mill.scalalib.bsp.BspBuildTarget
import mill.util.Version
import mill.{T, Task}

import scala.util.Properties

@experimental
trait SemanticDbJavaModule extends CoursierModule with mill.runner.api.SemanticDbJavaModuleApi {
  def jvmWorker: ModuleRef[JvmWorkerModule]
  def upstreamCompileOutput: T[Seq[CompilationResult]]
  def zincReportCachedProblems: T[Boolean]
  def zincIncrementalCompilation: T[Boolean]
  def allSourceFiles: T[Seq[PathRef]]
  def compile: T[mill.scalalib.api.CompilationResult]
  def bspBuildTarget: BspBuildTarget
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

  protected def semanticDbPluginIvyDeps: T[Seq[Dep]] = Task {
    val sv = semanticDbScalaVersion()
    val semDbVersion = semanticDbVersion()
    if (!JvmWorkerUtil.isScala3(sv) && semDbVersion.isEmpty) {
      val msg =
        """|
           |With Scala 2 you must provide a semanticDbVersion
           |
           |def semanticDbVersion = ???
           |""".stripMargin
      Result.Failure(msg)
    } else if (JvmWorkerUtil.isScala3(sv)) {
      Result.Success(Seq.empty[Dep])
    } else {
      Result.Success(Seq(
        ivy"org.scalameta:semanticdb-scalac_${sv}:${semDbVersion}"
      ))
    }
  }

  private def semanticDbJavaPluginIvyDeps: T[Seq[Dep]] = Task {
    val sv = semanticDbJavaVersion()
    if (sv.isEmpty) {
      val msg =
        """|
           |You must provide a javaSemanticDbVersion
           |
           |def semanticDbJavaVersion = ???
           |""".stripMargin
      Result.Failure(msg)
    } else {
      Result.Success(Seq(
        ivy"com.sourcegraph:semanticdb-javac:${sv}"
      ))
    }
  }

  /**
   * Scalac options to activate the compiler plugins.
   */
  protected def semanticDbEnablePluginScalacOptions: T[Seq[String]] = Task {
    val resolvedJars = defaultResolver().classpath(
      semanticDbPluginIvyDeps().map(_.exclude("*" -> "*"))
    )
    resolvedJars.iterator.map(jar => s"-Xplugin:${jar.path}").toSeq
  }

  protected def semanticDbPluginClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(semanticDbPluginIvyDeps())
  }

  protected def resolvedSemanticDbJavaPluginIvyDeps: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(semanticDbJavaPluginIvyDeps())
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
          (compileClasspath() ++ resolvedSemanticDbJavaPluginIvyDeps()).map(_.path),
        javacOptions = javacOpts,
        reporter = None,
        reportCachedProblems = zincReportCachedProblems(),
        incrementalCompilation = zincIncrementalCompilation()
      ).map(r =>
        SemanticDbJavaModule.copySemanticdbFiles(r.classes.path, Task.workspace, Task.dest / "data")
      )
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
  def bspCompiledClassesAndSemanticDbFiles: T[UnresolvedPath] = {
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

  def bspBuildTargetCompileSemanticDb = Task.Anon {
    compiledClassesAndSemanticDbFiles().path.toNIO
  }
}

object SemanticDbJavaModule {

  def javacOptionsTask(javacOptions: Seq[String], semanticDbJavaVersion: String)(implicit
      ctx: mill.api.Ctx
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
        }
      }
    PathRef(targetDir)
  }
}
