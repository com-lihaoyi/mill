package mill

import java.io.{InputStream, PrintStream}

import ammonite.main.Cli._
import ammonite.ops._
import ammonite.util.Util
import mill.eval.Evaluator
import mill.util.DummyInputStream


object ServerMain extends mill.clientserver.ServerMain[Evaluator.State]{
  def main0(args: Array[String],
            stateCache: Option[Evaluator.State],
            mainInteractive: Boolean,
            stdin: InputStream,
            stdout: PrintStream,
            stderr: PrintStream) = Main.main0(
    args,
    stateCache,
    mainInteractive,
    DummyInputStream,
    stdout,
    stderr
  )
}
object Main {

  def main(args: Array[String]): Unit = {
    val (result, _) = main0(
      args,
      None,
      ammonite.Main.isInteractive(),
      System.in,
      System.out,
      System.err
    )
    System.exit(if(result) 0 else 1)
  }

  def main0(args: Array[String],
            stateCache: Option[Evaluator.State],
            mainInteractive: Boolean,
            stdin: InputStream,
            stdout: PrintStream,
            stderr: PrintStream): (Boolean, Option[Evaluator.State]) = {
    import ammonite.main.Cli

    val removed = Set("predef-code", "no-home-predef")
    var interactive = false
    val interactiveSignature = Arg[Config, Unit](
      "interactive", Some('i'),
      "Run Mill in interactive mode, suitable for opening REPLs and taking user input",
      (c, v) =>{
        interactive = true
        c
      }
    )
    val millArgSignature =
      Cli.genericSignature.filter(a => !removed(a.name)) :+ interactiveSignature

    val millHome = mill.util.Ctx.defaultHome

    Cli.groupArgs(
      args.toList,
      millArgSignature,
      Cli.Config(home = millHome, remoteLogging = false)
    ) match{
      case _ if interactive =>
        stderr.println("-i/--interactive must be passed in as the first argument")
        (false, None)
      case Left(msg) =>
        stderr.println(msg)
        (false, None)
      case Right((cliConfig, _)) if cliConfig.help =>
        val leftMargin = millArgSignature.map(ammonite.main.Cli.showArg(_).length).max + 2
        stdout.println(
        s"""Mill Build Tool
           |usage: mill [mill-options] [target [target-options]]
           |
           |${formatBlock(millArgSignature, leftMargin).mkString(Util.newLine)}""".stripMargin
        )
        (true, None)
      case Right((cliConfig, leftoverArgs)) =>

        val repl = leftoverArgs.isEmpty
        if (repl && stdin == DummyInputStream) {
          stderr.println("Build repl needs to be run with the -i/--interactive flag")
          (false, stateCache)
        }else{
          val tqs = "\"\"\""
          val config =
            if(!repl) cliConfig
            else cliConfig.copy(
              predefCode =
                s"""import $$file.build, build._
                  |implicit val replApplyHandler = mill.main.ReplApplyHandler(
                  |  ammonite.ops.Path($tqs${cliConfig.home.toIO.getCanonicalPath.replaceAllLiterally("$", "$$")}$tqs),
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
            config.copy(colored = Some(mainInteractive)),
            stdout, stderr, stdin,
            stateCache
          )

          if (repl){
            runner.printInfo("Loading...")
            (runner.watchLoop(isRepl = true, printing = false, _.run()), runner.stateCache)
          } else {
            (runner.runScript(pwd / "build.sc", leftoverArgs), runner.stateCache)
          }
      }

    }
  }
}
