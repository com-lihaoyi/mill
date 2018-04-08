package mill.main
import java.io.{InputStream, OutputStream, PrintStream}

import ammonite.Main
import ammonite.interp.{Interpreter, Preprocessor}
import ammonite.ops.Path
import ammonite.util._
import mill.eval.{Evaluator, PathRef}
import mill.util.PrintLogger

import scala.annotation.tailrec


/**
  * Customized version of [[ammonite.MainRunner]], allowing us to run Mill
  * `build.sc` scripts with mill-specific tweaks such as a custom
  * `scriptCodeWrapper` or with a persistent evaluator between runs.
  */
class MainRunner(val config: ammonite.main.Cli.Config,
                 outprintStream: PrintStream,
                 errPrintStream: PrintStream,
                 stdIn: InputStream,
                 stateCache0: Option[Evaluator.State] = None,
                 env : Map[String, String])
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

  /**
    * Custom version of [[watchLoop]] that lets us generate the watched-file
    * signature only on demand, so if we don't have config.watch enabled we do
    * not pay the cost of generating it
    */
  @tailrec final def watchLoop2[T](isRepl: Boolean,
                                   printing: Boolean,
                                   run: Main => (Res[T], () => Seq[(Path, Long)])): Boolean = {
    val (result, watched) = run(initMain(isRepl))

    val success = handleWatchRes(result, printing)
    if (!config.watch) success
    else{
      watchAndWait(watched())
      watchLoop2(isRepl, printing, run)
    }
  }


  override def runScript(scriptPath: Path, scriptArgs: List[String]) =
    watchLoop2(
      isRepl = false,
      printing = true,
      mainCfg => {
        val (result, interpWatched) = RunScript.runScript(
          config.home,
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
          ),
          env
        )

        result match{
          case Res.Success(data) =>
            val (eval, evalWatches, res) = data

            stateCache = Some(Evaluator.State(eval.rootModule, eval.classLoaderSig, eval.workerCache, interpWatched))
            val watched = () => {
              val alreadyStale = evalWatches.exists(p => p.sig != PathRef(p.path, p.quick).sig)
              // If the file changed between the creation of the original
              // `PathRef` and the current moment, use random junk .sig values
              // to force an immediate re-run. Otherwise calculate the
              // pathSignatures the same way Ammonite would and hand over the
              // values, so Ammonite can watch them and only re-run if they
              // subsequently change
              if (alreadyStale) evalWatches.map(_.path -> util.Random.nextLong())
              else evalWatches.map(p => p.path -> Interpreter.pathSignature(p.path))
            }
            (Res(res), () => interpWatched ++ watched())
          case _ => (result, () => interpWatched)
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
    def apply(code: String,
              pkgName: Seq[ammonite.util.Name],
              imports: ammonite.util.Imports,
              printCode: String,
              indexedWrapperName: ammonite.util.Name,
              extraCode: String): (String, String, Int) = {
      val wrapName = indexedWrapperName.backticked
      val literalPath = pprint.Util.literalize(config.wd.toString)
      val top = s"""
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
        |  implicit lazy val millDiscover: mill.define.Discover[this.type] = mill.define.Discover[this.type]
        |}
        |
        |sealed trait $wrapName extends mill.main.MainModule{
        |""".stripMargin
      val bottom = "}"

      (top, bottom, 1)
    }
  }
}
