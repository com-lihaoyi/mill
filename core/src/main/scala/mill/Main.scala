package mill

import ammonite.ops._

object Main {
  case class Config(home: ammonite.ops.Path = pwd/'out/'ammonite,
                    colored: Option[Boolean] = None,
                    help: Boolean = false,
                    repl: Boolean = false,
                    watch: Boolean = false)

  def main(args: Array[String]): Unit = {

    import ammonite.main.Cli
    var repl = false
    var show = false
    val replCliArg = Cli.Arg[Cli.Config, Unit](
      "repl",
      None,
      "Open a Build REPL",
      (x, _) => {
        repl = true
        x
      }
    )
    val showCliArg = Cli.Arg[Cli.Config, Unit](
      "show",
      None,
      "Display the json-formatted value of the given target, if any",
      (x, _) => {
        show = true
        x
      }
    )
    Cli.groupArgs(
      args.toList,
      Cli.ammoniteArgSignature :+ replCliArg :+ showCliArg,
      Cli.Config()
    ) match{
      case Left(msg) =>
        System.err.println(msg)
        System.exit(1)
      case Right((cliConfig, leftoverArgs)) =>
        val config =
          if(!repl) cliConfig
          else cliConfig.copy(
            predefCode =
              """import $file.build, build._
                |implicit val replApplyHandler = mill.main.ReplApplyHandler(repl.pprinter(), build.mapping)
                |repl.pprinter() = replApplyHandler.pprinter
                |import replApplyHandler.generatedEval._
                |
              """.stripMargin,
            welcomeBanner = None
          )

        val runner = new mill.main.MainRunner(
          config, show,
          System.out, System.err, System.in, System.out, System.err
        )
        if (repl){
          runner.printInfo("Loading...")
          runner.runRepl()
        } else {
          val result = runner.runScript(pwd / "build.sc", leftoverArgs)
          System.exit(if(result) 0 else 1)
        }
    }
  }
}


