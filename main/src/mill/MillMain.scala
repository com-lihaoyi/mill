package mill

import java.io.{InputStream, PrintStream}

import scala.collection.JavaConverters._
import ammonite.main.Cli._
import io.github.retronym.java9rtexport.Export
import mill.eval.Evaluator
import mill.util.DummyInputStream

object MillMain {

  def main(args: Array[String]): Unit = {
    val as = args match {
      case Array(s, _*) if s == "-i" || s == "--interactive" => args.tail
      case _ => args
    }
    val (result, _) = main0(
      as,
      None,
      ammonite.Main.isInteractive(),
      System.in,
      System.out,
      System.err,
      System.getenv().asScala.toMap,
      b => ()
    )
    System.exit(if(result) 0 else 1)
  }

  def main0(args: Array[String],
            stateCache: Option[Evaluator.State],
            mainInteractive: Boolean,
            stdin: InputStream,
            stdout: PrintStream,
            stderr: PrintStream,
            env: Map[String, String],
            setIdle: Boolean => Unit): (Boolean, Option[Evaluator.State]) = {
    import ammonite.main.Cli
    
    val millHome = mill.util.Ctx.defaultHome

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



    var disableTicker = false
    val disableTickerSignature = Arg[Config, Unit](
      "disable-ticker", None,
      "Disable ticker log (e.g. short-lived prints of stages and progress bars)",
      (c, v) =>{
        disableTicker = true
        c
      }
    )

    var debugLog = false
    val debugLogSignature = Arg[Config, Unit](
      name = "debug", shortName = Some('d'),
      doc = "Show debug output on STDOUT",
      (c, v) => {
        debugLog = true
        c
      }
    )

    val millArgSignature =
      Cli.genericSignature.filter(a => !removed(a.name)) ++ Seq(interactiveSignature, disableTickerSignature, debugLogSignature)

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
           |${formatBlock(millArgSignature, leftMargin).mkString(ammonite.util.Util.newLine)}""".stripMargin
        )
        (true, None)
      case Right((cliConfig, leftoverArgs)) =>

        val repl = leftoverArgs.isEmpty
        if (repl && stdin == DummyInputStream) {
          stderr.println("Build repl needs to be run with the -i/--interactive flag")
          (false, stateCache)
        }else{
          val config =
            if(!repl) cliConfig
            else cliConfig.copy(
              predefCode =
                s"""import $$file.build, build._
                  |implicit val replApplyHandler = mill.main.ReplApplyHandler(
                  |  os.Path(${pprint.apply(cliConfig.home.toIO.getCanonicalPath.replaceAllLiterally("$", "$$")).plainText}),
                  |  $disableTicker,
                  |  interp.colors(),
                  |  repl.pprinter(),
                  |  build.millSelf.get,
                  |  build.millDiscover,
                  |  $debugLog
                  |)
                  |repl.pprinter() = replApplyHandler.pprinter
                  |import replApplyHandler.generatedEval._
                  |
                """.stripMargin,
              welcomeBanner = None
            )

          val runner = new mill.main.MainRunner(
            config.copy(colored = config.colored orElse Option(mainInteractive)),
            disableTicker,
            stdout, stderr, stdin,
            stateCache,
            env,
            setIdle,
            debugLog
          )

          if (mill.main.client.Util.isJava9OrAbove) {
            val rt = cliConfig.home / Export.rtJarName
            if (!os.exists(rt)) {
              runner.printInfo(s"Preparing Java ${System.getProperty("java.version")} runtime; this may take a minute or two ...")
              Export.rtTo(rt.toIO, false)
            }
          }

          if (repl){
            runner.printInfo("Loading...")
            (runner.watchLoop(isRepl = true, printing = false, _.run()), runner.stateCache)
          } else {
            (runner.runScript(os.pwd / "build.sc", leftoverArgs), runner.stateCache)
          }
      }

    }
  }
}
