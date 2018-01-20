package mill.main
import java.io.{InputStream, OutputStream, PrintStream}

import ammonite.Main
import ammonite.interp.{Interpreter, Preprocessor}
import ammonite.ops.Path
import ammonite.util._
import mill.define.Discover
import mill.eval.{Evaluator, PathRef}
import mill.util.PrintLogger
import upickle.Js

/**
  * Customized version of [[ammonite.MainRunner]], allowing us to run Mill
  * `build.sc` scripts with mill-specific tweaks such as a custom
  * `scriptCodeWrapper` or with a persistent evaluator between runs.
  */
class MainRunner(
    config: ammonite.main.Cli.Config,
    show: Boolean,
    outprintStream: PrintStream,
    errPrintStream: PrintStream,
    stdIn: InputStream)
    extends ammonite.MainRunner(
      config,
      outprintStream,
      errPrintStream,
      stdIn,
      outprintStream,
      errPrintStream
    ) {
  var lastEvaluator: Option[(Seq[(Path, Long)], Evaluator[_], Discover)] = None

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
          lastEvaluator,
          new PrintLogger(
            colors != ammonite.util.Colors.BlackWhite,
            colors,
            if (show) errPrintStream else outprintStream,
            errPrintStream,
            errPrintStream
          )
        )

        result match {
          case Res.Success(data) =>
            val (eval, discover, evaluationWatches, res) = data

            lastEvaluator = Some((interpWatched, eval, discover))

            (Res(res), interpWatched ++ evaluationWatches)
          case _ => (result, interpWatched)
        }
      }
    )

  override def handleWatchRes[T](res: Res[T], printing: Boolean) = {
    res match {
      case Res.Success(value) =>
        if (show) {
          for (json <- value.asInstanceOf[Seq[Js.Value]]) {
            outprintStream.println(json)
          }
        }

        true

      case _ => super.handleWatchRes(res, printing)
    }

  }
  override def initMain(isRepl: Boolean) = {
    super
      .initMain(isRepl)
      .copy(
        scriptCodeWrapper = CustomCodeWrapper,
        // Ammonite does not properly forward the wd from CliConfig to Main, so
        // force forward it outselves
        wd = config.wd
      )
  }
  object CustomCodeWrapper extends Preprocessor.CodeWrapper {
    def top(pkgName: Seq[Name], imports: Imports, indexedWrapperName: Name) = {
      val wrapName = indexedWrapperName.backticked
      s"""
         |package ${pkgName.head.encoded}
         |package ${Util.encodeScalaSourcePath(pkgName.tail)}
         |$imports
         |import mill._
         |
         |object $wrapName extends mill.define.BaseModule(ammonite.ops.Path(${pprint.Util
           .literalize(config.wd.toString)})) with $wrapName{
         |  // Stub to make sure Ammonite has something to call after it evaluates a script,
         |  // even if it does nothing...
         |  def $$main() = Iterator[String]()
         |
         |  val millDiscover: mill.define.Discover = mill.define.Discover[this.type]
         |  // Need to wrap the returned Module in Some(...) to make sure it
         |  // doesn't get picked up during reflective child-module discovery
         |  val millSelf = Some(this)
         |}
         |
         |sealed trait $wrapName extends mill.Module{
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
