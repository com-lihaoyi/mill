package mill

import java.io.{InputStream, PrintStream}

import ammonite.main.Cli._
import ammonite.ops._
import ammonite.util.Util
import mill.clientserver.{Client, FileLocks}
import mill.eval.Evaluator
import mill.util.DummyInputStream


object ClientMain {
  def initServer(lockBase: String) = {
    val selfJars = new java.lang.StringBuilder
    var current = getClass.getClassLoader
    while(current != null){
      getClass.getClassLoader match{
        case e: java.net.URLClassLoader =>
          val urls = e.getURLs
          var i = 0
          while(i < urls.length){
            if (selfJars.length() != 0) selfJars.append(':')
            selfJars.append(urls(i))
            i += 1
          }
        case _ =>
      }
      current = current.getParent
    }

    val l = new java.util.ArrayList[String]
    l.add("java")
    val props = System.getProperties
    val keys = props.stringPropertyNames().iterator()
    while(keys.hasNext){
      val k = keys.next()
      if (k.startsWith("MILL_")) l.add("-D" + k + "=" + props.getProperty(k))
    }
    l.add("-cp")
    l.add(selfJars.toString)
    l.add("mill.ServerMain")
    l.add(lockBase)
    new java.lang.ProcessBuilder()
      .command(l)
      .redirectOutput(new java.io.File(lockBase + "/logs"))
      .redirectError(new java.io.File(lockBase + "/logs"))
      .start()
  }
  def main(args: Array[String]): Unit = {
    val exitCode = Client.WithLock(1) { lockBase =>
      val c = new Client(
        lockBase,
        () => initServer(lockBase),
        new FileLocks(lockBase),
        System.in,
        System.out,
        System.err
      )
      c.run(args)
    }
    System.exit(exitCode)
  }
}
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

    val removed = Set("predef-code", "home", "no-home-predef")
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

    Cli.groupArgs(
      args.toList,
      millArgSignature,
      Cli.Config(remoteLogging = false)
    ) match{
      case _ if interactive =>
        stderr.println("-i/--interactive must be passed in as the first argument")
        (false, None)
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
