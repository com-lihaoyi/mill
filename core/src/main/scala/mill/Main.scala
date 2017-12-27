package mill

import ammonite.interp.{Interpreter, Preprocessor}
import ammonite.main.Scripts
import ammonite.ops._
import ammonite.util._
import mill.define.Task
import mill.discover._
import mill.eval.{Evaluator, Result}
import mill.util.{Logger, OSet, PrintLogger}
import ammonite.main.Scripts.pathScoptRead
import ammonite.repl.Repl
import ammonite.util.Util.normalizeNewlines
import mill.define.Task.TaskModule
object Main {





  case class Config(home: ammonite.ops.Path = pwd/'out/'ammonite,
                    colored: Option[Boolean] = None,
                    help: Boolean = false,
                    repl: Boolean = false,
                    watch: Boolean = false)

  def main(args: Array[String]): Unit = {

    import ammonite.main.Cli
    var repl = false
    val replCliArg = Cli.Arg[Cli.Config, Unit](
      "repl",
      None,
      "Open a Build REPL",
      (x, _) => {
        repl = true
        x
      }
    )
    Cli.groupArgs(
      args.toList,
      Cli.ammoniteArgSignature :+ replCliArg,
      Cli.Config()
    ) match{
      case Left(msg) =>
        System.err.println(msg)
        System.exit(1)
      case Right((cliConfig, leftoverArgs)) =>
        val config =
          if(!repl) cliConfig
          else cliConfig.copy(
            predefCode = "implicit val replApplyHandler = mill.main.ReplApplyHandler(mapping)",
            predefFile = Some(pwd/"build.sc"),
            welcomeBanner = None
          )

        val runner = new mill.main.MainRunner(config)
        if (repl){
          runner.printInfo("Loading...")
          runner.runRepl()
        } else {
          runner.runScript(pwd / "build.sc", leftoverArgs)
        }
    }
  }
}


