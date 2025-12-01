package mill.testkit

import mill.constants.OutFiles.OutFiles.{millDaemon, millNoDaemon, out}
import mill.constants.DaemonFiles.processId
import mill.util.Retry

trait IntegrationTesterBase {
  def workspaceSourcePath: os.Path
  def daemonMode: Boolean
  def cleanupProcessIdFile: Boolean

  def propagateJavaHome: Boolean

  def millTestSuiteEnv: Map[String, String] = {
    val javaHomeBin = sys.props("java.home") + "/bin"
    if (!propagateJavaHome) Map.empty
    else Map(
      "JAVA_HOME" -> sys.props("java.home"),
      "PATH" -> s"$javaHomeBin${System.getProperty("path.separator")}${sys.env("PATH")}"
    )
  }

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
    if (sys.env.contains("MILL_TEST_SHARED_OUTPUT_DIR")) baseWorkspacePath
    else Iterator
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
    println(s"Preparing integration test in $workspacePath")
    os.makeDir.all(workspacePath)
    if (!sys.env.contains("MILL_TEST_SHARED_OUTPUT_DIR")) {
      Retry(logger = Retry.printStreamLogger(System.err)) {
        val tmp = os.temp.dir()
        val outDir = os.Path(out, workspacePath)
        if (os.exists(outDir)) os.move.into(outDir, tmp)
        os.remove.all(tmp)
      }
      for (p <- os.list(workspacePath)) os.remove.all(p)
    } else{
      // if `MILL_TEST_SHARED_OUTPUT_DIR` is provided, keep `out/` intact
      // to re-use the daemon
      for (p <- os.list(workspacePath) if p.last != "out") os.remove.all(p)
    }



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

    // In case someone manually ran stuff in the integration test workspace earlier,
    // remove any leftover `out/` folder so it does not interfere with the test
    if (!sys.env.contains("MILL_TEST_SHARED_OUTPUT_DIR")) os.remove.all(workspacePath / "out")
  }

  /**
   * Remove any ID files to try and force them to exit
   */
  def removeProcessIdFile(): Unit = {
    if (!sys.env.contains("MILL_TEST_SHARED_OUTPUT_DIR")) {
      val outDir = os.Path(out, workspacePath)
      if (os.exists(outDir) && cleanupProcessIdFile) {
        if (daemonMode) {
          val serverPath = outDir / millDaemon
          os.remove(serverPath / processId)
        } else {
          val serverPath0 = outDir / millNoDaemon

          for (serverPath <- os.list.stream(serverPath0)) os.remove(serverPath / processId)

        }
        Thread.sleep(500) // give a moment for the server to notice the file is gone and exit
      }
    }
  }
}
