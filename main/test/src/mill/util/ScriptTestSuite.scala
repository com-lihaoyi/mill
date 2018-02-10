package mill.util

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}

import ammonite.ops._
import mill.util.ParseArgs
import utest._

abstract class ScriptTestSuite extends TestSuite{
  def workspaceSlug: String
  def scriptSourcePath: Path

  val workspacePath = pwd / 'target / 'workspace / workspaceSlug
  val stdOutErr = new PrintStream(new ByteArrayOutputStream())
//  val stdOutErr = new PrintStream(System.out)
  val stdIn = new ByteArrayInputStream(Array())
  val runner = new mill.main.MainRunner(
    ammonite.main.Cli.Config(wd = workspacePath),
    stdOutErr, stdOutErr, stdIn
  )
  def eval(s: String*) = runner.runScript(workspacePath / "build.sc", s.toList)
  def meta(s: String) = {
    val (List(selector), args) = ParseArgs.apply(Seq(s)).right.get

    read(workspacePath / "out" / selector._2.value.flatMap(_.pathSegments) / "meta.json")
  }


  def initWorkspace() = {
    rm(workspacePath)
    mkdir(workspacePath / up)
    // The unzipped git repo snapshots we get from github come with a
    // wrapper-folder inside the zip file, so copy the wrapper folder to the
    // destination instead of the folder containing the wrapper.

    cp(scriptSourcePath, workspacePath)
  }
}
