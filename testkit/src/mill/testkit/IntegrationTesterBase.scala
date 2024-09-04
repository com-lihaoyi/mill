package mill.testkit

trait IntegrationTesterBase {
  def workspaceSourcePath: os.Path

  /**
   * The working directory of the integration test suite, which is the root of the
   * Mill build being tested. Contains the `build.mill` file, any application code, and
   * the `out/` folder containing the build output
   *
   * Typically just `pwd`, which is a sandbox directory for test suites run using Mill.
   */
  val workspacePath: os.Path = os.pwd

  /**
   * Initializes the workspace in preparation for integration testing
   */
  def initWorkspace(): Unit = {
    println(s"Copying integration test sources from $workspaceSourcePath to $workspacePath")
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    // somehow os.copy does not properly preserve symlinks
    // os.copy(scriptSourcePath, workspacePath)
    os.call(("cp", "-R", workspaceSourcePath, workspacePath))
    os.remove.all(workspacePath / "out")
  }
}
