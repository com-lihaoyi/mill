package mill.standalone

trait GitRepoStandaloneTestSuite extends utest.TestSuite, StandaloneTestSuite {

  def gitRepoUrl: String
  def gitRepoBranch: String
  def gitRepoDepth: Int = 1

  def workspacePath = {
    val cwd = os.temp.dir(dir = os.pwd, deleteOnExit = false)
    // preserve repo dir name for a realistic reproduction
    os.proc("git", "clone", gitRepoUrl, "--depth", gitRepoDepth, "--branch", gitRepoBranch)
      .call(cwd = cwd)
    os.list(cwd).head
  }
}
