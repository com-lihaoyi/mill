package mill.testkit
import mill.constants.OutFiles.{millServer, millNoServer, out}
import mill.constants.ServerFiles.processId
import mill.util.Retry

trait IntegrationTesterBase {
  def workspaceSourcePath: os.Path
  def clientServerMode: Boolean

  def propagateJavaHome: Boolean

  def millTestSuiteEnv: Map[String, String] =
    if (propagateJavaHome) Map("JAVA_HOME" -> sys.props("java.home"))
    else Map()

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
  def removeProcessIdFile(): Unit = {
    try {
      println("removeProcessIdFile 0")
      val outDir = os.Path(out, workspacePath)
      println("removeProcessIdFile 1")
      if (os.exists(outDir)) {
        println("removeProcessIdFile 2")
        val serverPath0 = outDir / (if (clientServerMode) millServer else millNoServer)
        println("removeProcessIdFile 3")
        for (serverPath <- os.list.stream(serverPath0)) os.remove(serverPath / processId)
        println("removeProcessIdFile 4")
        Thread.sleep(500) // give a moment for the server to notice the file is gone and exit
        println("removeProcessIdFile 5")
      }
    } catch {
      case e: Throwable =>
        println("removeProcessIdFile catch" + e)
        e.printStackTrace()
        throw e
    }
  }
}
