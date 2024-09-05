package mill.testkit

import os.Path
import utest._

abstract class IntegrationTestSuiteBase extends TestSuite {
  protected def workspaceSourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))
  protected val clientServerMode: Boolean = sys.env("MILL_INTEGRATION_SERVER_MODE").toBoolean

  protected def millExecutable: Path = os.Path(System.getenv("MILL_INTEGRATION_LAUNCHER"), os.pwd)
}
