package millbuild

import mill.*
import mill.scalalib.*
import mill.javalib.api.JvmWorkerUtil
import mill.api.BuildCtx
import com.goyeau.mill.scalafix.ScalafixModule
import coursier.Repository

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

  def ciScalacOptions: T[Seq[String]] = Task {
    if (isCI()) {
      // Turn warnings into errors on CI
      if (isScala3()) Seq("-Werror") else Seq("-Xfatal-warnings")
    } else Nil
  }

  protected def javaRelease: String = "17"

  def scalacOptions =
    super.scalacOptions() ++ Seq(
      "--release",
      javaRelease,
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
    override def repositoriesTask: Task[Seq[Repository]] =Task.Anon {
      (super[ScalaTests].repositoriesTask() ++ super[MillJavaModule].repositoriesTask()).distinct
    }
    def scalafixConfig = Task { Some(BuildCtx.workspaceRoot / ".scalafix.conf") }
    def forkArgs = super.forkArgs() ++ outer.testArgs()
    def moduleDeps = outer.testModuleDeps
    def mvnDeps = super.mvnDeps() ++ outer.testMvnDeps()
    def forkEnv = super.forkEnv() ++ outer.testForkEnv()
  }
}
