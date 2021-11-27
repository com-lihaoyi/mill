package mill.util

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}

import mainargs.Flag
import scala.util.Try

import os.Path
import utest._

abstract class ScriptTestSuite(fork: Boolean) extends TestSuite {
  def workspaceSlug: String
  def scriptSourcePath: os.Path
  def buildPath: os.SubPath = os.sub / "build.sc"

  val workspacePath = os.pwd / "target" / "workspace" / workspaceSlug
  val wd = workspacePath / buildPath / os.up
  val stdOutErr = System.out // new PrintStream(new ByteArrayOutputStream())
  val stdIn = new ByteArrayInputStream(Array())
  val disableTicker = false
  val debugLog = false
  val keepGoing = false
  val systemProperties = Map[String, String]()
  val threadCount = sys.props.get("MILL_THREAD_COUNT").map(_.toInt).orElse(Some(1))
  lazy val runner = new mill.main.MainRunner(
    config = ammonite.main.Config(
      ammonite.main.Config.Core(
        noDefaultPredef = Flag(),
        silent = Flag(),
        watch = Flag(),
        bsp = Flag(),
        thin = Flag(),
        help = Flag(),
        showVersion = Flag()
      ),
      ammonite.main.Config.Predef(noHomePredef = Flag()),
      ammonite.main.Config.Repl(noRemoteLogging = Flag(), classBased = Flag())
    ),
    mainInteractive = false,
    disableTicker = disableTicker,
    outprintStream = stdOutErr,
    errPrintStream = stdOutErr,
    stdIn = stdIn,
    stateCache0 = None,
    env = Map.empty,
    setIdle = b => (),
    debugLog = debugLog,
    keepGoing = keepGoing,
    systemProperties = systemProperties,
    threadCount = threadCount,
    ringBell = false,
    wd = wd,
    initialSystemProperties = sys.props.toMap
  )
  def eval(s: String*): Boolean = {
    if (!fork) runner.runScript(workspacePath / buildPath, s.toList)
    else {
      try {
        os.proc(os.home / "mill-release", "-i", s).call(
          wd,
          stdin = os.Inherit,
          stdout = os.Inherit,
          stderr = os.Inherit
        )
        true
      } catch { case e: Throwable => false }
    }
  }
  def meta(s: String): String = {
    val (List(selector), args) = ParseArgs.apply(Seq(s), multiSelect = false).right.get

    val segments = selector._2.value.flatMap(_.pathSegments)
    os.read(wd / "out" / segments.init / s"${segments.last}.json")
  }

  def initWorkspace(): Path = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    // The unzipped git repo snapshots we get from github come with a
    // wrapper-folder inside the zip file, so copy the wrapper folder to the
    // destination instead of the folder containing the wrapper.

    os.copy(scriptSourcePath, workspacePath)
    workspacePath
  }
}
