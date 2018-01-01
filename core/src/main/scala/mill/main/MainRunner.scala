package mill.main
import java.io.{InputStream, OutputStream, PrintStream}

import ammonite.interp.{Interpreter, Preprocessor}
import ammonite.ops.Path
import ammonite.util.{Imports, Name, Res, Util}
import mill.discover.Discovered
import mill.eval.{Evaluator, PathRef}
import upickle.Js

/**
  * Customized version of [[ammonite.MainRunner]], allowing us to run Mill
  * `build.sc` scripts with mill-specific tweaks such as a custom
  * `scriptCodeWrapper` or with a persistent evaluator between runs.
  */
class MainRunner(config: ammonite.main.Cli.Config, show: Boolean,
                 outprintStream: PrintStream,
                 errPrintStream: PrintStream,
                 stdIn: InputStream,
                 stdOut: OutputStream,
                 stdErr: OutputStream)
  extends ammonite.MainRunner(config, outprintStream, errPrintStream, stdIn, stdOut, stdErr){
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
              mainCfg.wd, scriptPath, interp, scriptArgs, lastEvaluator,
              errPrintStream, errPrintStream
            )
            result match{
              case Res.Success(data) =>
                val (eval, evaluationWatches0, res) = data
                val alreadyStale = evaluationWatches0.exists(p => p.sig != PathRef(p.path, p.quick).sig)
                // If the file changed between the creation of the original
                // `PathRef` and the current moment, use random junk .sig values
                // to force an immediate re-run. Otherwise calculate the
                // pathSignatures the same way Ammonite would and hand over the
                // values, so Ammonite can watch them and only re-run if they
                // subsequently change
                val evaluationWatches =
                  if (alreadyStale) evaluationWatches0.map(_.path -> util.Random.nextLong())
                  else evaluationWatches0.map(p => p.path -> Interpreter.pathSignature(p.path))

                lastEvaluator = Some((interpWatched, eval))
                (Res(res.map(_.flatMap(_._2))), interpWatched ++ evaluationWatches)
              case _ =>
                (result, interpWatched)
            }


        }
      }
    )

  override def handleWatchRes[T](res: Res[T], printing: Boolean) = {
    res match{
      case Res.Success(value) =>
        if (show){
          for(json <- value.asInstanceOf[Seq[Js.Value]]){
            System.out.println(json)
          }
        }

        true

      case _ => super.handleWatchRes(res, printing)
    }

  }
  override def initMain(isRepl: Boolean) = {
    super.initMain(isRepl).copy(
      scriptCodeWrapper = mill.main.MainRunner.CustomCodeWrapper,
      // Ammonite does not properly forward the wd from CliConfig to Main, so
      // force forward it outselves
      wd = config.wd
    )
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