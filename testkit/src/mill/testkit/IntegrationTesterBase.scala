package mill.testkit
import mill.main.client.OutFiles.{out, millWorker}
import mill.main.client.ServerFiles.serverId

trait IntegrationTesterBase {
  def workspaceSourcePath: os.Path

  /**
   * The working directory of the integration test suite, which is the root of the
   * Mill build being tested. Contains the `build.mill` file, any application code, and
   * the `out/` folder containing the build output
   */
  val workspacePath: os.Path = os.pwd

  /**
   * Initializes the workspace in preparation for integration testing
   */
  def initWorkspace(): Unit = {
    println(s"Copying integration test sources from $workspaceSourcePath to $workspacePath")
    os.makeDir.all(workspacePath)
    os.list(workspacePath).foreach(os.remove.all(_))
    os.list(workspaceSourcePath).filter(_.last != out).foreach(os.copy.into(_, workspacePath))
    os.remove.all(workspacePath / "out")
  }

  /**
   * Remove any ID files to try and force them to exit
   */
  def removeServerIdFile(): Unit = {
    val serverIdFiles = for {
      outPath <- os.list.stream(workspacePath / out)
      if outPath.last.startsWith(millWorker)
    } yield outPath / serverId

    serverIdFiles.foreach(os.remove(_))
    Thread.sleep(500) // give a moment for the server to notice the file is gone and exit
  }
}
