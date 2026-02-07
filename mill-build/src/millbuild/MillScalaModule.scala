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

  def semanticDbVersion = Deps.semanticDBscala.version

  def isScala3: T[Boolean] = Task { JvmWorkerUtil.isScala3(scalaVersion()) }

  override def mapDependencies = super[MillJavaModule].mapDependencies

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
    def forkArgs = super.forkArgs() ++ outer.testArgs()
    def moduleDeps = outer.testModuleDeps
    def mvnDeps = super.mvnDeps() ++ outer.testMvnDeps()
    def forkEnv = super.forkEnv() ++ outer.testForkEnv()
    override def repositoriesTask = super[MillJavaModule].repositoriesTask
    override def mapDependencies = super[MillJavaModule].mapDependencies

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
