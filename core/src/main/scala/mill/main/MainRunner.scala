package mill.main
import ammonite.ops.Path
import ammonite.util.Res
import mill.discover.Discovered
import mill.eval.Evaluator

class MainRunner(config: ammonite.main.Cli.Config)
  extends ammonite.MainRunner(
    config,
    System.out, System.err, System.in, System.out, System.err
  ){
  var lastEvaluator: Option[(Seq[(Path, Long)], Discovered.Mapping[_], Evaluator)] = None
  override def runScript(scriptPath: Path, scriptArgs: List[String]) =
    watchLoop(
      isRepl = false,
      printing = true,
      mainCfg => {
        mainCfg.instantiateInterpreter() match{
          case Left(problems) => problems
          case Right(interp) =>
            val result = RunScript.runScript(
              mainCfg.wd, scriptPath, interp, scriptArgs, lastEvaluator
            )

            val interpWatched = interp.watchedFiles
            result match{
              case Res.Success((mapping, eval, evaluationWatches, success)) =>
                lastEvaluator = Some((interpWatched, mapping, eval))
                (result, interpWatched ++ evaluationWatches)
              case _ =>
                (result, interpWatched)
            }


        }
      }
    )
  override def initMain(isRepl: Boolean) = {
    super.initMain(isRepl).copy(scriptCodeWrapper = mill.main.CustomCodeWrapper)
  }
  override def handleWatchRes[T](res: Res[T], printing: Boolean) = {
    super.handleWatchRes(res, printing = false)
  }
}
