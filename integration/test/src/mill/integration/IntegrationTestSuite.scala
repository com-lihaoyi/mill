package mill.integration

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, PrintStream}

import ammonite.ops._
import mill.define.Segments
import mill.main.ParseArgs
import utest._

abstract class IntegrationTestSuite(repoKey: String, workspaceSlug: String) extends TestSuite{
  val workspacePath = pwd / 'target / 'workspace / workspaceSlug
  val buildFilePath = pwd / 'integration / 'test / 'resources / workspaceSlug
  val stdOutErr = new PrintStream(new ByteArrayOutputStream())
//  val stdOutErr = new PrintStream(System.out)
  val stdIn = new ByteArrayInputStream(Array())
  val runner = new mill.main.MainRunner(
    ammonite.main.Cli.Config(wd = workspacePath), false,
    stdOutErr, stdOutErr, stdIn
  )
  def eval(s: String*) = runner.runScript(workspacePath / "build.sc", s.toList)
  def meta(s: String) = {
    val (List(selector), args) = ParseArgs.apply(Seq(s)).right.get

    read(workspacePath / "out" / selector.flatMap(_.pathSegments) / "meta.json")
  }
  def initWorkspace() = {
    rm(workspacePath)
    mkdir(workspacePath / up)
    // The unzipped git repo snapshots we get from github come with a
    // wrapper-folder inside the zip file, so copy the wrapper folder to the
    // destination instead of the folder containing the wrapper.
    val path = sys.props(repoKey)
    val Seq(wrapper) = ls(Path(path))
    cp(wrapper, workspacePath)
    cp(buildFilePath / "build.sc", workspacePath / "build.sc")
    assert(!ls.rec(workspacePath).exists(_.ext == "class"))
  }
}
