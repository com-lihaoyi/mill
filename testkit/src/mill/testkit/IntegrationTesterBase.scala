package mill.testkit
import mill.main.client.OutFiles.out

trait IntegrationTesterBase {
  def workspaceSourcePath: os.Path

  /**
   * The working directory of the integration test suite, which is the root of the
   * Mill build being tested. Contains the `build.mill` file, any application code, and
   * the `out/` folder containing the build output
   *
   * Typically just `pwd`, which is a sandbox directory for test suites run using Mill.
   */
  val workspacePath: os.Path = os.temp(dir = os.pwd)

  /**
   * Initializes the workspace in preparation for integration testing
   */
  def initWorkspace(): Unit = {
    println(s"Copying integration test sources from $workspaceSourcePath to $workspacePath")
    os.list(workspacePath).foreach(os.remove.all(_))
    os.list(workspaceSourcePath).filter(_.last != out).foreach(os.copy.into(_, workspacePath))
    os.remove.all(workspacePath / "out")
  }
}
