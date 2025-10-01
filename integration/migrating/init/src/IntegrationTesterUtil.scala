package mill.integration

import mill.javalib.publish.*
import mill.testkit.IntegrationTester
import upickle.default.*

object IntegrationTesterUtil {

  def showNamed[A](tester: IntegrationTester, showArgs: String*)(using
      Reader[Map[String, A]]
  ): Map[String, A] = upickle.default.read(tester.eval("showNamed" +: showArgs).out)

  def showNamedRepositories(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[Seq[String]](tester, selectorPrefix + "__.repositories")
  def showNamedJvmId(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[String](tester, selectorPrefix + "__.jvmId")
  def showNamedMandatoryMvnDeps(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[Seq[String]](tester, selectorPrefix + "__.mandatoryMvnDeps")
  def showNamedMvnDeps(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[Seq[String]](tester, selectorPrefix + "__.mvnDeps")
  def showNamedCompileMvnDeps(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[Seq[String]](tester, selectorPrefix + "__.compileMvnDeps")
  def showNamedRunMvnDeps(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[Seq[String]](tester, selectorPrefix + "__.runMvnDeps")
  def showNamedBomMvnDeps(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[Seq[String]](tester, selectorPrefix + "__.bomMvnDeps")
  def showNamedJavacOptions(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[Seq[String]](tester, selectorPrefix + "__.javacOptions")

  def showNamedPomParentProject(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[Option[Artifact]](tester, selectorPrefix + "__.pomParentProject")
  def showNamedPomSettings(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[PomSettings](tester, selectorPrefix + "__.pomSettings")
  def showNamedPublishVersion(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[String](tester, selectorPrefix + "__.publishVersion")
  def showVersionScheme(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[Option[VersionScheme]](tester, selectorPrefix + "__.versionScheme")
  def showNamedPublishProperties(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[Map[String, String]](tester, selectorPrefix + "__.publishProperties")

  def showNamedErrorProneVersion(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[String](tester, selectorPrefix + "__.errorProneVersion")
  def showNamedErrorProneDeps(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[Seq[String]](tester, selectorPrefix + "__.errorProneDeps")
  def showNamedErrorProneOptions(tester: IntegrationTester, selectorPrefix: String = "") =
    showNamed[Seq[String]](tester, selectorPrefix + "__.errorProneOptions")
}
