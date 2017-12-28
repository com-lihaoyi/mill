package mill.main
import ammonite.interp.Preprocessor
import ammonite.ops.Path
import ammonite.util.{Imports, Name, Res, Util}
import mill.discover.Discovered
import mill.eval.Evaluator

/**
  * Customized version of [[ammonite.MainRunner]], allowing us to run Mill
  * `build.sc` scripts with mill-specific tweaks such as a custom
  * `scriptCodeWrapper` or with a persistent evaluator between runs.
  */
class MainRunner(config: ammonite.main.Cli.Config)
  extends ammonite.MainRunner(
    config,
    System.out, System.err, System.in, System.out, System.err
  ){
  var lastEvaluator: Option[(Seq[(Path, Long)], Evaluator[_])] = None
  override def runScript(scriptPath: Path, scriptArgs: List[String]) =
    watchLoop(
      isRepl = false,
      printing = true,
      mainCfg => {
        mainCfg.instantiateInterpreter() match{
          case Left(problems) => problems
          case Right(interp) =>
            val interpWatched = interp.watchedFiles

            val result = RunScript.runScript(
              mainCfg.wd, scriptPath, interp, scriptArgs, lastEvaluator
            )
            result match{
              case Res.Success(data) =>
                val (eval, evaluationWatches) = data
                lastEvaluator = Some((interpWatched, eval))
                (result, interpWatched ++ evaluationWatches)
              case _ =>
                (result, interpWatched)
            }


        }
      }
    )
  override def initMain(isRepl: Boolean) = {
    super.initMain(isRepl).copy(scriptCodeWrapper = mill.main.MainRunner.CustomCodeWrapper)
  }
  override def handleWatchRes[T](res: Res[T], printing: Boolean) = {
    super.handleWatchRes(res, printing = false)
  }
}

object MainRunner{
  object CustomCodeWrapper extends Preprocessor.CodeWrapper {
    def top(pkgName: Seq[Name], imports: Imports, indexedWrapperName: Name) = {
      val wrapName = indexedWrapperName.backticked
      s"""
         |package ${pkgName.head.encoded}
         |package ${Util.encodeScalaSourcePath(pkgName.tail)}
         |$imports
         |import mill._
         |
         |object $wrapName extends $wrapName{
         |  // Stub to make sure Ammonite has something to call after it evaluates a script,
         |  // even if it does nothing...
         |  def $$main() = Iterator[String]()
         |  lazy val mapping = mill.discover.Discovered.make[$wrapName].mapping(this)
         |}
         |
         |sealed abstract class $wrapName extends mill.Module{
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