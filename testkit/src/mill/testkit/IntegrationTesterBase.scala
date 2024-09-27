package mill.testkit
import mill.api.Retry
import mill.main.client.OutFiles.{millServer, out}
import mill.main.client.ServerFiles.serverId

trait IntegrationTesterBase {
  def workspaceSourcePath: os.Path

  /**
   * The working directory of the integration test suite, which is the root of the
   * Mill build being tested. Contains the `build.mill` file, any application code, and
   * the `out/` folder containing the build output
   *
   * Each integration test that runs in the same [[baseWorkspacePath]] is given a new folder
   * for isolation purposes; even though we try our best to clean up the processes and files
   * from each Mill run, it still doesn't work 100%, and re-using the same folder can cause
   * non-deterministic interference and flakiness
   */
  val workspacePath: os.Path = {
    Iterator
      .iterate(1)(_ + 1)
      .map(i => baseWorkspacePath / s"run-$i")
      .find(!os.exists(_))
      .head
  }

  val baseWorkspacePath: os.Path

  /**
   * Initializes the workspace in preparation for integration testing
   */
  def initWorkspace(): Unit = {
    println(s"Copying integration test sources from $workspaceSourcePath to $workspacePath")
    os.makeDir.all(workspacePath)
    Retry() {
      val tmp = os.temp.dir()
      val outDir = os.Path(out, workspacePath)
      if (os.exists(outDir)) os.move.into(outDir, tmp)
      os.remove.all(tmp)
    }

    os.list(workspacePath).foreach(os.remove.all(_))
    val outRelPathOpt = os.FilePath(out) match {
      case relPath: os.RelPath if relPath.ups == 0 => Some(relPath)
      case _ => None
    }
    os.list(workspaceSourcePath)
      .filter(
        outRelPathOpt match {
          case None => _ => true
          case Some(outRelPath) => !_.endsWith(outRelPath)
        }
      )
      .foreach(os.copy.into(_, workspacePath))
  }

  /**
   * Remove any ID files to try and force them to exit
   */
  def removeServerIdFile(): Unit = {
    val outDir = os.Path(out, workspacePath)
    if (os.exists(outDir)) {
      val serverIdFiles = for {
        outPath <- os.list.stream(outDir)
        if outPath.last.startsWith(millServer)
      } yield outPath / serverId

      serverIdFiles.foreach(os.remove(_))
      Thread.sleep(500) // give a moment for the server to notice the file is gone and exit
    }
  }
}
