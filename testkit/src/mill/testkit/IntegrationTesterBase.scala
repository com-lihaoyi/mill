package mill.testkit

trait IntegrationTesterBase {
  def workspaceSourcePath: os.Path
  private val workspacePathBase = os.pwd / "out" / "integration-tester-workdir"
  os.makeDir.all(workspacePathBase)

  /**
   * The working directory of the integration test suite, which is the root of the
   * Mill build being tested. Contains the `build.mill` file, any application code, and
   * the `out/` folder containing the build output
   *
   * Make sure it lives inside `os.pwd` because somehow the tests fail on windows
   * if it lives in the global temp folder.
   */
  val workspacePath: os.Path = os.temp.dir(workspacePathBase, deleteOnExit = false)

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
