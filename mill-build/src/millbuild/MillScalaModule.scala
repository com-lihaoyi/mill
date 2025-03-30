package millbuild

import mill._
import mill.scalalib._
import com.goyeau.mill.scalafix.ScalafixModule
import mill.scalalib.api.ZincWorkerUtil

/**
 * Some custom scala settings and test convenience
 */
trait MillScalaModule extends ScalaModule with MillJavaModule with ScalafixModule { outer =>
  def scalaVersion = Deps.scalaVersion
  def scalapVersion: T[String] = Deps.scala2Version
  def scalafixScalaBinaryVersion = T {
    def sv = scalaVersion()
    if (ZincWorkerUtil.isScala3(sv)) "2.13"
    else ZincWorkerUtil.scalaBinaryVersion(sv)
  }

  def scalafixConfig = T { Some(T.workspace / ".scalafix.conf") }

  def semanticDbVersion = Deps.semanticDBscala.version

  def scaladocOptions = Seq("-Xsource:3")

  def scalacOptions =
    super.scalacOptions() ++ Seq(
      "-deprecation",
      "-feature"
    ) ++ (
      if (ZincWorkerUtil.isScala3(scalaVersion())) Seq(
        // "-Werror",
        "-Wunused:all",
        // "-Xfatal-warnings",
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
        // "-rewrite", "-source", "3.6-migration"
      )
      else Seq(
        "-P:acyclic:force",
        // "-Xfatal-warnings",
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

  def scalacPluginIvyDeps = T {
    val sv = scalaVersion()
    val binaryVersion = ZincWorkerUtil.scalaBinaryVersion(sv)
    val hasModuleDefs = binaryVersion == "2.13" || binaryVersion == "3"
    super.scalacPluginIvyDeps() ++
      when(binaryVersion != "3")(Deps.acyclic) ++
      when(hasModuleDefs)(Deps.millModuledefsPlugin)
  }

  def mandatoryIvyDeps = T {
    val sv = scalaVersion()
    val binaryVersion = ZincWorkerUtil.scalaBinaryVersion(sv)
    val hasModuleDefs = binaryVersion == "2.13" || binaryVersion == "3"
    super.mandatoryIvyDeps() ++
      when(hasModuleDefs)(Deps.millModuledefs)
  }

  /** Default tests module. */
  lazy val test: MillScalaTests = new MillScalaTests {}
  trait MillScalaTests extends ScalaTests with MillJavaModule with MillBaseTestsModule
      with ScalafixModule {
    def scalafixConfig = T { Some(T.workspace / ".scalafix.conf") }
    def forkArgs = super.forkArgs() ++ outer.testArgs()
    def moduleDeps = outer.testModuleDeps
    def ivyDeps = super.ivyDeps() ++ outer.testIvyDeps()
    def forkEnv = super.forkEnv() ++ outer.testForkEnv()
  }
}
