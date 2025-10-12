package mill.integration

import mill.javalib.publish.*
import mill.testkit.IntegrationTester
import upickle.default.*

object IntegrationTesterUtil {

  def showNamed[A](tester: IntegrationTester, showArgs: String*)(using
      Reader[Map[String, A]]
  ): Map[String, A] = upickle.default.read(tester.eval("showNamed" +: showArgs).out)

  def showNamedRepositories(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, Seq[String]] = showNamed(tester, selector + "repositories")

  def showNamedJvmId(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, String] = showNamed(tester, selector + "jvmId")

  def showNamedMvnDeps(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, Seq[String]] = showNamed(tester, selector + "mvnDeps")
  def showNamedCompileMvnDeps(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, Seq[String]] = showNamed(tester, selector + "compileMvnDeps")
  def showNamedRunMvnDeps(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, Seq[String]] = showNamed(tester, selector + "runMvnDeps")
  def showNamedBomMvnDeps(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, Seq[String]] = showNamed(tester, selector + "bomMvnDeps")
  def showNamedJavacOptions(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, Seq[String]] = showNamed(tester, selector + "javacOptions")

  def showNamedPomParentProject(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, Option[Artifact]] = showNamed(tester, selector + "pomParentProject")
  def showNamedPomSettings(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, PomSettings] = showNamed[PomSettings](tester, selector + "pomSettings")
    .map((k, v) => (k, v.copy(description = v.description.stripMargin)))
  def showNamedPublishVersion(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, String] = showNamed(tester, selector + "publishVersion")
  def showNamedVersionScheme(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, Option[VersionScheme]] = showNamed(tester, selector + "versionScheme")
  def showNamedPublishProperties(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, Map[String, String]] =
    showNamed[Map[String, String]](tester, selector + "publishProperties")

  def showNamedErrorProneVersion(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, String] = showNamed(tester, selector + "errorProneVersion")
  def showNamedErrorProneDeps(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, Seq[String]] = showNamed(tester, selector + "errorProneDeps")
  def showNamedErrorProneOptions(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, Seq[String]] = showNamed(tester, selector + "errorProneOptions")

  def showNamedScalaVersion(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, String] = showNamed(tester, selector + "scalaVersion")
  def showNamedScalacOptions(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, Seq[String]] = showNamed(tester, selector + "scalacOptions")
  def showNamedScalacPluginMvnDeps(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, Seq[String]] = showNamed(tester, selector + "scalacPluginMvnDeps")

  def showNamedScalaJSVersion(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, String] = showNamed(tester, selector + "scalaJSVersion")
  def showNamedScalaJSModuleKind(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, String] = showNamed(tester, selector + "moduleKind")

  def showNamedScalaNativeVersion(
      tester: IntegrationTester,
      selector: String = "__."
  ): Map[String, String] = showNamed(tester, selector + "scalaNativeVersion")

  def renderAllImportedConfigurations(tester: IntegrationTester) = {
    import tester.eval
    val repositories = eval(("show", "__.repositories")).out
    val jvmId = eval(("show", "__.jvmId")).out
    val mvnDeps = eval(("show", "__.mvnDeps")).out
    val compileMvnDeps = eval(("show", "__.compileMvnDeps")).out
    val runMvnDeps = eval(("show", "__.runMvnDeps")).out
    val bomMvnDeps = eval(("show", "__.bomMvnDeps")).out
    val showModuleDeps = eval("__.showModuleDeps").out
    val javacOptions = eval(("show", "__.javacOptions")).out
    val pomParentProject = eval(("show", "__.pomParentProject")).out
    val pomSettings = eval(("show", "__.pomSettings")).out
    val publishVersion = eval(("show", "__.publishVersion")).out
    val versionScheme = eval(("show", "__.versionScheme")).out
    val publishProperties = eval(("show", "__.publishProperties")).out
    val errorProneVersion = eval(("show", "__.errorProneVersion")).out
    val errorProneDeps = eval(("show", "__.errorProneDeps")).out
    val errorProneJavacEnableOptions = eval(("show", "errorProneJavacEnableOptions")).out
    val errorProneOptions = eval(("show", "__.errorProneOptions")).out
    val scalaVersion = eval(("show", "__.scalaVersion")).out
    val scalacOptions = eval(("show", "__.scalacOptions")).out
    val scalacPluginMvnDeps = eval(("show", "__.scalacPluginMvnDeps")).out
    val scalaJSVersion = eval(("show", "__.scalaJSVersion")).out
    val moduleKind = eval(("show", "__.moduleKind")).out
    val scalaNativeVersion = eval(("show", "__.scalaNativeVersion")).out
    s"""$repositories
       |$jvmId
       |$mvnDeps
       |$compileMvnDeps
       |$runMvnDeps
       |$bomMvnDeps
       |$showModuleDeps
       |$javacOptions
       |$pomParentProject
       |$pomSettings
       |$publishVersion
       |$versionScheme
       |$publishProperties
       |$errorProneVersion
       |$errorProneDeps
       |$errorProneJavacEnableOptions
       |$errorProneOptions
       |$scalaVersion
       |$scalacOptions
       |$scalacPluginMvnDeps
       |$scalaJSVersion
       |$moduleKind
       |$scalaNativeVersion
       |""".stripMargin
  }
}
