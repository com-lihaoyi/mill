package mill

import java.io.{InputStream, OutputStream, PrintStream}

import ammonite.main.Cli
import ammonite.main.Cli.{formatBlock, genericSignature, replSignature}
import ammonite.ops._
import ammonite.util.Util
import mill.main.MainRunner

object Main {
  def main(args: Array[String]): Unit = {
    val (result, _) = main0(
      args,
      None,
      ammonite.Main.isInteractive(), () => false,
      System.in,
      System.out,
      System.err
    )
    System.exit(if(result) 0 else 1)
  }
  def main0(args: Array[String],
            mainRunner: Option[(Cli.Config, MainRunner)],
            mainInteractive: Boolean,
            watchInterrupted: () => Boolean,
            stdin: InputStream,
            stdout: PrintStream,
            stderr: PrintStream): (Boolean, Option[(Cli.Config, MainRunner)]) = {
    import ammonite.main.Cli

    val removed = Set("predef-code", "home", "no-home-predef")
    val millArgSignature = Cli.genericSignature.filter(a => !removed(a.name))
    Cli.groupArgs(
      args.toList,
      millArgSignature,
      Cli.Config(remoteLogging = false)
    ) match{
      case Left(msg) =>
        System.err.println(msg)
        (false, None)
      case Right((cliConfig, _)) if cliConfig.help =>
        val leftMargin = millArgSignature.map(ammonite.main.Cli.showArg(_).length).max + 2
        System.out.println(
        s"""Mill Build Tool
           |usage: mill [mill-options] [target [target-options]]
           |
           |${formatBlock(millArgSignature, leftMargin).mkString(Util.newLine)}""".stripMargin
        )
        (true, None)
      case Right((cliConfig, leftoverArgs)) =>

        val repl = leftoverArgs.isEmpty
        val config =
          if(!repl) cliConfig
          else cliConfig.copy(
            predefCode =
              """import $file.build, build._
                |implicit val replApplyHandler = mill.main.ReplApplyHandler(
                |  interp.colors(),
                |  repl.pprinter(),
                |  build.millSelf.get,
                |  build.millDiscover
                |)
                |repl.pprinter() = replApplyHandler.pprinter
                |import replApplyHandler.generatedEval._
                |
              """.stripMargin,
            welcomeBanner = None
          )

        val runner = new mill.main.MainRunner(
          config.copy(home = pwd / "out" / ".ammonite", colored = Some(mainInteractive)),
          stdout, stderr, stdin,
          watchInterrupted,
          mainRunner match{
            case Some((c, mr)) if c.copy(storageBackend = null) == cliConfig.copy(storageBackend = null) =>
              mr.lastEvaluator
            case _ => None
          }
        )

        if (repl){
          runner.printInfo("Loading...")
          (runner.watchLoop(isRepl = true, printing = false, _.run()), Some(cliConfig -> runner))
        } else {
          (runner.runScript(pwd / "build.sc", leftoverArgs), Some(cliConfig -> runner))
        }
    }
  }
}