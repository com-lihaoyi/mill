package mill.testkit

abstract class UtestIntegrationTestSuite extends utest.TestSuite with IntegrationTestSuite {
  export mill.testkit.{asTestValue, withTestClues}

  protected def workspaceSourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
  protected def daemonMode: Boolean = sys.env("MILL_INTEGRATION_DAEMON_MODE").toBoolean

  /** Whether the Mill JARs are published locally alongside this Mill launcher */
  protected def isPackagedLauncher: Boolean =
    sys.env("MILL_INTEGRATION_IS_PACKAGED_LAUNCHER").toBoolean
  protected def millExecutable: os.Path =
    os.Path(System.getenv("MILL_INTEGRATION_LAUNCHER"), os.pwd)
}
