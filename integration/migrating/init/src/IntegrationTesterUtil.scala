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
}
