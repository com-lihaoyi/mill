package mill.standalone

trait StandaloneTestSuite {

  protected def millExecutable: os.Path =
    os.Path(System.getenv("MILL_STANDALONE_EXECUTABLE"), os.pwd)

  protected def daemonMode: Boolean =
    sys.env("MILL_STANDALONE_DAEMON_MODE").toBoolean

  def debugLog = false

  protected def propagateJavaHome: Boolean = true

  def workspacePath: os.Path

  def standaloneTest[T](block: StandaloneTester => T): T = {
    val tester = StandaloneTester(
      millExecutable,
      daemonMode,
      debugLog,
      workspacePath,
      propagateJavaHome
    )
    block(tester)
  }
}
