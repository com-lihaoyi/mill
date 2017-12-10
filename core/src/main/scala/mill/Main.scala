package mill

import ammonite.interp.Interpreter
import ammonite.main.Scripts
import ammonite.ops._
import ammonite.util.{Colors, Res}
import mill.define.Task
import mill.discover._
import mill.eval.{Evaluator, Result}
import mill.util.{Logger, OSet, PrintLogger}
import ammonite.main.Scripts.pathScoptRead
import ammonite.repl.Repl
import mill.define.Task.TaskModule
object Main {
  def parseSelector(input: String) = {
    import fastparse.all._
    val segment = P( CharsWhileIn(('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')).! ).map(
      Mirror.Segment.Label
    )
    val crossSegment = P( "[" ~ CharsWhile(c => c != ',' && c != ']').!.rep(1, sep=",") ~ "]" ).map(
      Mirror.Segment.Cross
    )
    val query = P( segment ~ ("." ~ segment | crossSegment).rep ~ End ).map{
      case (h, rest) => h :: rest.toList
    }
    query.parse(input)
  }



  def parseArgs(selectorString: String): Either[String, List[Mirror.Segment]] = {
    import fastparse.all.Parsed
    if (selectorString.isEmpty) Left("Selector cannot be empty")
    else parseSelector(selectorString) match {
      case f: Parsed.Failure => Left(s"Parsing exception ${f.msg}")
      case Parsed.Success(selector, _) => Right(selector)
    }
  }


  def discoverMirror[T: Discovered](obj: T): Either[String, Discovered[T]] = {
    val discovered = implicitly[Discovered[T]]
    val consistencyErrors = Discovered.consistencyCheck(obj, discovered)
    if (consistencyErrors.nonEmpty) {
      Left(s"Failed Discovered.consistencyCheck: ${consistencyErrors.map(Mirror.renderSelector)}")
    } else {
      Right(discovered)
    }
  }

  def evaluate(evaluator: Evaluator,
               target: Task[Any],
               watch: Path => Unit): Option[String] = {
    val evaluated = evaluator.evaluate(OSet(target))
    evaluated.transitive.foreach {
      case t: define.Source => watch(t.handle.path)
      case _ => // do nothing
    }

    val errorStr =
      (for((k, fs) <- evaluated.failing.items()) yield {
        val ks = k match{
          case Left(t) => t.toString
          case Right(t) => Mirror.renderSelector(t.segments.toList)
        }
        val fss = fs.map{
          case Result.Exception(t) => t.toString
          case Result.Failure(t) => t
        }
        s"$ks ${fss.mkString(", ")}"
      }).mkString("\n")

    evaluated.failing.keyCount match {
      case 0 => None
      case n => Some(s"$n targets failed\n$errorStr")
    }
  }

  def apply[T: Discovered](args: Seq[String],
                           obj: T,
                           watch: Path => Unit,
                           coloredOutput: Boolean): Int = {

    val log = new PrintLogger(coloredOutput)

    val Seq(selectorString, rest @_*) = args

    val res = for {
      sel <- parseArgs(selectorString)
      disc <- discoverMirror(obj)
      crossSelectors = sel.map{
        case Mirror.Segment.Cross(x) => x.toList.map(_.toString)
        case _ => Nil
      }
      target <- mill.main.Resolve.resolve(sel, disc.mirror, obj, rest, crossSelectors, Nil)
      evaluator = new Evaluator(pwd / 'out, Discovered.mapping(obj)(disc), log)
      _ <- evaluate(evaluator, target, watch).toLeft(())
    } yield ()

    res match {
      case Left(err) =>
        log.error(err)
        1
      case Right(_) => 0
    }
  }

  case class Config(home: ammonite.ops.Path = pwd/'out/'ammonite,
                    colored: Option[Boolean] = None,
                    help: Boolean = false,
                    repl: Boolean = false,
                    watch: Boolean = false)

  def main(args: Array[String]): Unit = {
    val syntheticPath = pwd / 'out / "run.sc"
    write.over(
      syntheticPath,
      s"""import $$file.^.build
         |import mill._
         |
         |@main def run(args: String*) = mill.Main(args, build, interp.watch, true/*interp.colors()*/)
         |
         |@main def idea() = mill.scalaplugin.GenIdea(build)""".stripMargin
    )

    import ammonite.main.Cli
    var repl = false
    val replCliArg = Cli.Arg[Cli.Config, Unit](
      "repl",
      None,
      "Open a Build REPL",
      (x, _) => {
        repl = true
        x
      }
    )
    Cli.groupArgs(
      args.toList,
      Cli.ammoniteArgSignature :+ replCliArg,
      Cli.Config()
    ) match{
      case Left(msg) =>
        System.err.println(msg)
        System.exit(1)
      case Right((cliConfig, leftoverArgs)) =>
        if (repl){

          val runner = new ammonite.MainRunner(
            cliConfig.copy(
              predefFile = Some(pwd / "build.sc"),
              welcomeBanner = Some("")
            ),
            System.out, System.err,
            System.in, System.out, System.err
          )
          runner.printInfo("Loading...")
          runner.runRepl()
        } else {
          val runner = new ammonite.MainRunner(
            cliConfig,
            System.out, System.err,
            System.in, System.out, System.err
          )
          runner.runScript(syntheticPath, leftoverArgs)
        }
    }
  }
}

