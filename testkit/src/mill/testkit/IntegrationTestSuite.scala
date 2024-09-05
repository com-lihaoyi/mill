package mill.testkit


abstract class UtestIntegrationTestSuite extends utest.TestSuite with IntegrationTestSuite {
  protected def workspaceSourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_FOLDER"))
  protected def clientServerMode: Boolean = sys.env("MILL_INTEGRATION_SERVER_MODE").toBoolean
  protected def millExecutable: os.Path =
    os.Path(System.getenv("MILL_INTEGRATION_LAUNCHER"), os.pwd)
}
