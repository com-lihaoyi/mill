package mill.testkit

import os.Path
import utest._

abstract class IntegrationTestSuiteBase extends TestSuite {
  def workspaceSourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))
  val clientServerMode: Boolean = sys.env("MILL_INTEGRATION_SERVER_MODE").toBoolean

  def millExecutable: Path = os.Path(System.getenv("MILL_INTEGRATION_LAUNCHER"), os.pwd)
}
