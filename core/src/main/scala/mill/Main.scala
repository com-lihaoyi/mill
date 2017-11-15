package mill

import ammonite.Main
import ammonite.main.Scripts
import ammonite.ops._
import ammonite.util.{Colors, Name, Res}
import mill.define.Task
import mill.discover._
import mill.eval.Evaluator
import mill.util.OSet
import play.api.libs.json.Format

import scala.annotation.tailrec
import ammonite.main.Scripts.pathScoptRead
import ammonite.repl.Repl
object Main {
  def apply[T: Discovered](args: Seq[String], obj: T, watch: Path => Unit) = {
    val startTime = System.currentTimeMillis()
    val Seq(selectorString, rest @_*) = args
    val selector = selectorString.split('.')
    val discovered = implicitly[Discovered[T]]
    val consistencyErrors = Discovered.consistencyCheck(obj, discovered)
    if (consistencyErrors.nonEmpty) println("Failed Discovered.consistencyCheck: " + consistencyErrors)
    else {
      val mapping = Discovered.mapping(obj)(discovered)
      val workspacePath = pwd / 'out

      val mainRoutes = discovered.mains.map(x => (x.path :+ x.entryPoint.name, x: Info[T, _]))
      val targetRoutes = discovered.targets.map(x => (x.path, x: Info[T, _]))
      val routeList: Seq[(Seq[String], Info[T, _])] = mainRoutes ++ targetRoutes
      val routeMap = routeList.toMap
      routeMap.get(selector) match{
        case Some(info) =>
          val target = getTarget(obj, info, rest.toList)
          val evaluator = new Evaluator(workspacePath, mapping)
          val evaluated = evaluator.evaluate(OSet(target))

          val delta = System.currentTimeMillis() - startTime
          println(fansi.Color.Blue("Finished in " + delta/1000.0 + "s"))
          evaluated.transitive.foreach{
            case t: define.Source => watch(t.handle.path)
            case _ => // do nothing
          }

        case None => println("Unknown selector: " + selector.mkString("."))
      }
    }
  }

  def getTarget[T](obj: T, info: Info[T, _], args: List[String]) = info match{
    case nestedEntryPoint: CommandInfo[T, _] =>
      nestedEntryPoint.invoke(
        obj,
        ammonite.main.Scripts.groupArgs(args)
      ) match{
        case error: Router.Result.Error =>
          throw new Exception("Failed to evaluate main method: " + error)
        case mill.discover.Router.Result.Success(target) => target
      }
    case labelled: LabelInfo[T, _] => labelled.run(obj)
  }


  def main(args: Array[String]): Unit = {
    case class Config(home: ammonite.ops.Path = pwd/'out/'ammonite,
                      colored: Option[Boolean] = None,
                      help: Boolean = false,
                      repl: Boolean = false,
                      watch: Boolean = false)
    import ammonite.main.Cli.Arg
    val signature = Seq(
      Arg[Config, Path](
        "home", Some('h'),
        "The home directory of the REPL; where it looks for config and caches",
        (c, v) => c.copy(home = v)
      ),
      Arg[Config, Unit](
        "help", None,
        """Print this message""".stripMargin,
        (c, v) => c.copy(help = true)
      ),
      Arg[Config, Boolean](
        "color", None,
        """Enable or disable colored output; by default colors are enabled
          |in both REPL and scripts if the console is interactive, and disabled
          |otherwise""".stripMargin,
        (c, v) => c.copy(colored = Some(v))
      ),
      Arg[Config, Unit](
        "repl", Some('r'),
        "Open a build REPL",
        (c, v) => c.copy(repl = true)
      ),
      Arg[Config, Unit](
        "watch", Some('w'),
        "Watch and re-run your build when it changes",
        (c, v) => c.copy(watch = true)
      )
    )
    ammonite.main.Cli.groupArgs(args.toList, signature, Config()) match{
      case Left(err) =>
      case Right((config, leftover)) =>
        if (config.help){
          val leftMargin = signature.map(ammonite.main.Cli.showArg(_).length).max + 2
          println(ammonite.main.Cli.formatBlock(signature, leftMargin).mkString("\n"))
        }else if (config.repl){
          val repl = ammonite.Main(
            predefFile = Some(pwd/"build.sc")
          ).instantiateRepl(remoteLogger = None)
          repl.right.get.interp.initializePredef()
          repl.right.get.run()
        }else {
          val interp = ammonite.Main(
            predefFile = Some(pwd/"build.sc")
          ).instantiateInterpreter()

          interp.right.get.initializePredef()
          val syntheticPath = pwd/'out/"run.sc"
          write.over(
            syntheticPath,
            """@main def run(args: String*) = mill.Main(args, ammonite.predef.FilePredef, interp.watch)
              |
              |@main def idea() = mill.scalaplugin.GenIdea(ammonite.predef.FilePredef)
            """.stripMargin
          )

          val res = ammonite.main.Scripts.runScript(
            pwd,
            syntheticPath,
            interp.right.get,
            Scripts.groupArgs(leftover)
          )

          handleWatchRes(res, true)
        }
    }
  }
  def handleWatchRes[T](res: Res[T], printing: Boolean) = {
    val success = res match {
      case Res.Failure(msg) =>
        println(msg)
        false
      case Res.Exception(ex, s) =>
        println(
          Repl.showException(ex, fansi.Color.Red, fansi.Attr.Reset, fansi.Color.Green)
        )
        false

      case Res.Success(value) =>
        if (printing && value != ()) println(pprint.PPrinter.BlackWhite(value))
        true

      case Res.Skip   => true // do nothing on success, everything's already happened
    }
    success
  }
}
