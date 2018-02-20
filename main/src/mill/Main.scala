package mill

import ammonite.main.Cli.{formatBlock, genericSignature, replSignature}
import ammonite.ops._
import ammonite.util.Util

object Main {
  case class Config(home: ammonite.ops.Path = pwd/'out/'ammonite,
                    colored: Option[Boolean] = None,
                    help: Boolean = false,
                    repl: Boolean = false,
                    watch: Boolean = false)

  def main(args: Array[String]): Unit = {
    val result = main0(args)
    System.exit(if(result) 0 else 1)
  }
  def main0(args: Array[String]): Boolean = {

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
        false
      case Right((cliConfig, _)) if cliConfig.help =>
        val leftMargin = millArgSignature.map(ammonite.main.Cli.showArg(_).length).max + 2
        System.out.println(
        s"""Mill Build Tool
           |usage: mill [mill-options] [target [target-options]]
           |
           |${formatBlock(millArgSignature, leftMargin).mkString(Util.newLine)}""".stripMargin
        )
        true
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
          config.copy(home = pwd / "out" / ".ammonite"),
          System.out, System.err, System.in
        )
        if (repl){
          runner.printInfo("Loading...")
          runner.watchLoop(isRepl = true, printing = false, _.run())
        } else {
          runner.runScript(pwd / "build.sc", leftoverArgs)
        }
    }
  }
}