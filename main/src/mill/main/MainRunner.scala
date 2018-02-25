package mill.main
import java.io.{InputStream, OutputStream, PrintStream}

import ammonite.interp.{Interpreter, Preprocessor}
import ammonite.ops.Path
import ammonite.util._
import mill.eval.{Evaluator, PathRef}

import mill.util.PrintLogger


/**
  * Customized version of [[ammonite.MainRunner]], allowing us to run Mill
  * `build.sc` scripts with mill-specific tweaks such as a custom
  * `scriptCodeWrapper` or with a persistent evaluator between runs.
  */
class MainRunner(val config: ammonite.main.Cli.Config,
                 outprintStream: PrintStream,
                 errPrintStream: PrintStream,
                 stdIn: InputStream,
                 stateCache0: Option[Evaluator.State] = None)
  extends ammonite.MainRunner(
    config, outprintStream, errPrintStream,
    stdIn, outprintStream, errPrintStream
  ){

  var stateCache  = stateCache0

  override def watchAndWait(watched: Seq[(Path, Long)]) = {
    printInfo(s"Watching for changes to ${watched.length} files... (Ctrl-C to exit)")
    def statAll() = watched.forall{ case (file, lastMTime) =>
      Interpreter.pathSignature(file) == lastMTime
    }

    while(statAll()) Thread.sleep(100)
  }

  override def runScript(scriptPath: Path, scriptArgs: List[String]) =
    watchLoop(
      isRepl = false,
      printing = true,
      mainCfg => {
        val (result, interpWatched) = RunScript.runScript(
          mainCfg.wd,
          scriptPath,
          mainCfg.instantiateInterpreter(),
          scriptArgs,
          stateCache,
          new PrintLogger(
            colors != ammonite.util.Colors.BlackWhite,
            colors,
            outprintStream,
            errPrintStream,
            errPrintStream,
            stdIn
          )
        )

        result match{
          case Res.Success(data) =>
            val (eval, evaluationWatches, res) = data

            stateCache = Some(Evaluator.State(eval.rootModule, eval.classLoaderSig, eval.workerCache, interpWatched))

            (Res(res), interpWatched ++ evaluationWatches)
          case _ => (result, interpWatched)
        }
      }
    )

  override def handleWatchRes[T](res: Res[T], printing: Boolean) = {
    res match{
      case Res.Success(value) => true
      case _ => super.handleWatchRes(res, printing)
    }
  }

  override def initMain(isRepl: Boolean) = {
    super.initMain(isRepl).copy(
      scriptCodeWrapper = CustomCodeWrapper,
      // Ammonite does not properly forward the wd from CliConfig to Main, so
      // force forward it outselves
      wd = config.wd
    )
  }

  object CustomCodeWrapper extends Preprocessor.CodeWrapper {
    def top(pkgName: Seq[Name], imports: Imports, indexedWrapperName: Name) = {
      val wrapName = indexedWrapperName.backticked
      val literalPath = pprint.Util.literalize(config.wd.toString)
      s"""
         |package ${pkgName.head.encoded}
         |package ${Util.encodeScalaSourcePath(pkgName.tail)}
         |$imports
         |import mill._
         |object $wrapName
         |extends mill.define.BaseModule(ammonite.ops.Path($literalPath))
         |with $wrapName{
         |  // Stub to make sure Ammonite has something to call after it evaluates a script,
         |  // even if it does nothing...
         |  def $$main() = Iterator[String]()
         |
         |  // Need to wrap the returned Module in Some(...) to make sure it
         |  // doesn't get picked up during reflective child-module discovery
         |  def millSelf = Some(this)
         |
         |  implicit def millDiscover: mill.define.Discover[this.type] = mill.define.Discover[this.type]
         |}
         |
         |sealed trait $wrapName extends mill.main.MainModule{
         |""".stripMargin
    }


    def bottom(printCode: String, indexedWrapperName: Name, extraCode: String) = {
      // We need to disable the `$main` method definition inside the wrapper class,
      // because otherwise it might get picked up by Ammonite and run as a static
      // class method, which blows up since it's defined as an instance method
      "\n}"
    }
  }
}
