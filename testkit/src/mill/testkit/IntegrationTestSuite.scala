package mill.testkit

import os.Path
import utest._

abstract class IntegrationTestSuite extends TestSuite with IntegrationTester.Impl {
  protected def workspaceSourcePath: os.Path = os.Path(sys.env("MILL_INTEGRATION_REPO_ROOT"))
  val clientServerMode: Boolean = sys.env("MILL_INTEGRATION_SERVER_MODE").toBoolean

  def millExecutable: Path = os.Path(System.getenv("MILL_INTEGRATION_LAUNCHER"), os.pwd)
  override def utestAfterEach(path: Seq[String]): Unit = {
    if (clientServerMode) close()
  }
}
