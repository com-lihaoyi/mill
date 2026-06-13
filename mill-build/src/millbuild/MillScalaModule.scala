package millbuild

import mill.*
import mill.scalalib.*
import mill.javalib.api.JvmWorkerUtil
import mill.api.BuildCtx
import com.goyeau.mill.scalafix.ScalafixModule
import mill.javalib.testrunner.TestResult
import upickle.implicits.namedTuples.default.given

/**
 * Some custom scala settings and test convenience
 */
trait MillScalaModule extends ScalaModule with MillJavaModule with ScalafixModule { outer =>
  def scalaVersion = Deps.scalaVersion
  def scalapVersion: T[String] = Deps.scala2Version
  def scalafixScalaBinaryVersion = Task {
    def sv = scalaVersion()
    if (JvmWorkerUtil.isScala3(sv)) "2.13"
    else JvmWorkerUtil.scalaBinaryVersion(sv)
  }

  def scalafixConfig = Task { Some(BuildCtx.workspaceRoot / ".scalafix.conf") }

  // Replicate `ScalafixModule.fix`, but invoke scalafix under a raw path serializer so the
  // working directory (and source/classpath/config paths) resolve to real absolute paths rather
  // than the `../mill-workspace` reproducible-build alias, which scalafix rejects with
  // "working directory must be relative: ../mill-workspace". We can't just wrap `super.fix(...)`
  // because Mill evaluates that as a separate super-task outside this command's dynamic scope.
  override def fix(args: String*) = Task.Command {
    MillScalaModule.runScalafix(
      Task.ctx().log,
      scalafixRepositories(),
      ScalafixModule.filesToFix(sources()).map(_.path),
      (
        compileClasspath() ++
          localClasspath() ++
          Seq(semanticDbDataDetailed().compilationResult.classes)
      ).map(_.path),
      scalaVersion(),
      scalacOptions(),
      scalafixIvyDeps(),
      scalafixConfig(),
      args,
      BuildCtx.workspaceRoot
    )
  }

  def semanticDbVersion = Deps.semanticDBscala_runtime.version

  def isScala3: T[Boolean] = Task { JvmWorkerUtil.isScala3(scalaVersion()) }

  override def resolutionParams = super[MillJavaModule].resolutionParams

  def ciScalacOptions: T[Seq[String]] = Task {
    if (isFatalWarnings()) {
      // Turn warnings into errors on CI
      if (isScala3()) Seq("-Werror") else Seq("-Xfatal-warnings")
    } else Nil
  }

  def scalacOptions =
    super.scalacOptions() ++ Seq(
      "-deprecation",
      "-feature"
    ) ++ ciScalacOptions() ++ (
      if (isScala3()) Seq(
        "-explain-cyclic",
        "-Wunused:all",
        "-Wconf:msg=An existential type that came from a Scala-2 classfile:silent",
        "-Wconf:msg=import scala.language.implicitConversions:silent",
        "-Wconf:msg=IterableOnceExtensionMethods:silent",
        "-Wconf:msg=is deprecated:silent",
        "-Wconf:msg=cannot be checked at runtime:silent"
        // "-Wconf:msg=unused:silent"
        // "-no-indent",
        // "-Wvalue-discard",
        // "-Wshadow:all",
        // "-Wsafe-init",
        // "-Wnonunit-statement",
        // "-Wimplausible-patterns",
        // "-rewrite", "-source", "3.7-migration"
      )
      else Seq(
        "-P:acyclic:force",
        "-Xlint:unused",
        "-Xlint:adapted-args",
        "-Xsource:3",
        "-Wconf:msg=inferred type changes:silent",
        "-Wconf:msg=case companions no longer extend FunctionN:silent",
        "-Wconf:msg=access modifiers for:silent",
        "-Wconf:msg=is deprecated:silent",
        "-Wconf:msg=found in a package prefix of the required type:silent"
      )
    )

  def scalacPluginMvnDeps = Task {
    val sv = scalaVersion()
    val binaryVersion = JvmWorkerUtil.scalaBinaryVersion(sv)
    val hasModuleDefs = binaryVersion == "2.13" || binaryVersion == "3"
    super.scalacPluginMvnDeps() ++
      Option.when(binaryVersion != "3")(Deps.acyclic) ++
      Option.when(hasModuleDefs)(Deps.millModuledefsPlugin) ++
      Seq(Deps.unrollPlugin)
  }

  def mandatoryMvnDeps = Task {
    val sv = scalaVersion()
    val binaryVersion = JvmWorkerUtil.scalaBinaryVersion(sv)
    val hasModuleDefs = binaryVersion == "2.13" || binaryVersion == "3"
    super.mandatoryMvnDeps() ++
      Option.when(hasModuleDefs)(Deps.millModuledefs) ++
      Seq(Deps.unrollAnnotation)
  }

  /** Default tests module. */
  lazy val test: MillScalaTests = new MillScalaTests {}
  trait MillScalaTests extends ScalaTests with MillJavaModule with MillBaseTestsModule
      with ScalafixModule {
    def scalafixConfig = Task { Some(BuildCtx.workspaceRoot / ".scalafix.conf") }
    override def fix(args: String*) = Task.Command {
      MillScalaModule.runScalafix(
        Task.ctx().log,
        scalafixRepositories(),
        ScalafixModule.filesToFix(sources()).map(_.path),
        (
          compileClasspath() ++
            localClasspath() ++
            Seq(semanticDbDataDetailed().compilationResult.classes)
        ).map(_.path),
        this.scalaVersion(),
        scalacOptions(),
        scalafixIvyDeps(),
        scalafixConfig(),
        args,
        BuildCtx.workspaceRoot
      )
    }
    def forkArgs = super.forkArgs() ++ outer.testArgs()
    def moduleDeps = outer.testModuleDeps
    def mvnDeps = super.mvnDeps() ++ outer.testMvnDeps()
    def forkEnv = super.forkEnv() ++ outer.testForkEnv()
    override def repositoriesTask = super[MillJavaModule].repositoriesTask
    override def resolutionParams = super[MillJavaModule].resolutionParams

    def selectiveInputs: Seq[Task[?]] = null
    override def testForked(args: String*) = Task.Command(selectiveInputs = selectiveInputs) {
      super.testForked(args*)()
    }

    override def scalacOptions = Task {
      val base = super.scalacOptions().filterNot(_ == "-Wunused:all")
      val sv = outer.scalaVersion()
      val (unusedFlag, unusedWconf) =
        if (JvmWorkerUtil.isScala3(sv)) (Seq("-Wunused:all"), Seq("-Wconf:msg=unused:silent"))
        else if (sv.startsWith("2.13")) (Seq("-Wunused"), Seq("-Wconf:cat=unused:silent"))
        else if (sv.startsWith("2.12")) (Seq("-Ywarn-unused"), Seq("-Wconf:cat=unused:silent"))
        else (Nil, Nil)
      // Tests frequently use unused-pattern bindings for assertions; keep those warnings off.
      base ++ unusedFlag ++ unusedWconf
    }
  }
}

object MillScalaModule {

  /**
   * Replicates `ScalafixModule.fix`, but reconciles Mill's reproducible-build path relativization
   * with scalafix, which needs real absolute paths and matches source files to their semanticdb by
   * relativizing them against the working directory.
   */
  def runScalafix(
      log: mill.api.daemon.Logger,
      repositories: Seq[coursier.core.Repository],
      sourcesToFix: Seq[os.Path],
      classpath: Seq[os.Path],
      scalaVersion: String,
      scalacOptions: Seq[String],
      scalafixDeps: Seq[mill.javalib.Dep],
      config: Option[os.Path],
      args: Seq[String],
      workspaceRoot: os.Path
  ): mill.api.daemon.Result[Unit] = {
    val aliasSegments = Seq("out", "mill-daemon", "mill-workspace")
    val realWorkspaceRoot = mill.api.PathRef.toResolvedOsPath(workspaceRoot)
    val aliasWorkspaceRoot = realWorkspaceRoot / aliasSegments

    // Scala 3.7.x records semanticdb paths through Mill's alias symlink for Java 11 modules.
    // Keep the working directory real, but pass source files through the same symlink so scalafix's
    // source-relative semanticdb lookup matches those modules.
    val useAliasSources = mill.api.internal.PathAliasing.withRawPathSerializer {
      classpath.exists(cp => os.isDir(cp / "META-INF" / "semanticdb" / aliasSegments))
    }
    val effectiveSources =
      if (useAliasSources) {
        sourcesToFix.map { source =>
          val resolved = mill.api.PathRef.toResolvedOsPath(source)
          if (resolved.startsWith(realWorkspaceRoot)) {
            aliasWorkspaceRoot / resolved.subRelativeTo(realWorkspaceRoot)
          } else source
        }
      } else {
        sourcesToFix.map(mill.api.PathRef.toResolvedOsPath)
      }

    mill.api.internal.PathAliasing.withRawPathSerializer {
      ScalafixModule.fixAction(
        log,
        repositories,
        effectiveSources,
        classpath.map(mill.api.PathRef.toResolvedOsPath),
        scalaVersion,
        scalacOptions,
        scalafixDeps,
        config.map(mill.api.PathRef.toResolvedOsPath),
        args,
        realWorkspaceRoot
      )
    }
  }
}
