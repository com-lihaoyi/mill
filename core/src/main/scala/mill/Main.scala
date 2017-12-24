package mill

import ammonite.interp.{Interpreter, Preprocessor}
import ammonite.main.Scripts
import ammonite.ops._
import ammonite.util._
import mill.define.Task
import mill.discover._
import mill.eval.{Evaluator, Result}
import mill.util.{Logger, OSet, PrintLogger}
import ammonite.main.Scripts.pathScoptRead
import ammonite.repl.Repl
import ammonite.util.Util.normalizeNewlines
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


  def consistencyCheck[T](mapping: Discovered.Mapping[T]): Either[String, Unit] = {
    val consistencyErrors = Discovered.consistencyCheck(mapping)
    if (consistencyErrors.nonEmpty) {
      Left(s"Failed Discovered.consistencyCheck: ${consistencyErrors.map(Mirror.renderSelector)}")
    } else {
      Right(())
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

  def apply[T](args: Seq[String],
               mapping: Discovered.Mapping[T],
               watch: Path => Unit,
               coloredOutput: Boolean): Int = {

    val log = new PrintLogger(coloredOutput)

    val Seq(selectorString, rest @_*) = args

    val res = for {
      sel <- parseArgs(selectorString)
      _ <- consistencyCheck(mapping)
      crossSelectors = sel.map{
        case Mirror.Segment.Cross(x) => x.toList.map(_.toString)
        case _ => Nil
      }
      target <- mill.main.Resolve.resolve(sel, mapping.mirror, mapping.base, rest, crossSelectors, Nil)
      evaluator = new Evaluator(pwd / 'out, mapping.value, log, sel)
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
        val config =
          if(!repl) cliConfig
          else cliConfig.copy(
            defaultPredef = false,
            predefFile = Some(pwd/"build.sc"),
            welcomeBanner = None
          )

        val runner = new ammonite.MainRunner(
          config,
          System.out, System.err,
          System.in, System.out, System.err
        ){
          override def initMain(isRepl: Boolean) = {
            super.initMain(isRepl).copy(scriptCodeWrapper = customCodeWrapper)
          }
        }

        if (repl){
          runner.printInfo("Loading...")
          runner.runRepl()
        } else {
          runner.runScript(pwd / "build.sc", leftoverArgs)
        }
    }
  }
  val customCodeWrapper = new Preprocessor.CodeWrapper {
    def top(pkgName: Seq[Name], imports: Imports, indexedWrapperName: Name) = {
      s"""
      |package ${pkgName.head.encoded}
      |package ${Util.encodeScalaSourcePath(pkgName.tail)}
      |$imports
      |import mill._
      |sealed abstract class ${indexedWrapperName.backticked} extends mill.Module{\n
      |""".stripMargin
    }

    def bottom(printCode: String, indexedWrapperName: Name, extraCode: String) = {
      val wrapName = indexedWrapperName.backticked
      val tmpName = ammonite.util.Name(indexedWrapperName.raw + "-Temp").backticked

      // Define `discovered` in the `tmpName` trait, before mixing in `MainWrapper`,
      // to ensure that `$tempName#discovered` is initialized before `MainWrapper` is.
      //
      // `import $wrapName._` is necessary too let Ammonite pick up all the
      // members of class wrapper, which are inherited but otherwise not visible
      // in the AST of the `$wrapName` object
      //
      // We need to duplicate the Ammonite predef as part of the wrapper because
      // imports within the body of the class wrapper are not brought into scope
      // by the `import $wrapName._`. Other non-Ammonite-predef imports are not
      // made available, and that's just too bad
      s"""
        |}
        |trait $tmpName{
        |  val discovered = mill.discover.Discovered.make[$wrapName]
        |  val interpApi = ammonite.interp.InterpBridge.value
        |}
        |
        |object $wrapName
        |extends $wrapName
        |with $tmpName
        |with mill.MainWrapper[$wrapName] {
        |  @ammonite.main.Router.main
        |  def idea() = mill.scalaplugin.GenIdea(mapping)
        |  ${ammonite.main.Defaults.replPredef}
        |  ${ammonite.main.Defaults.predefString}
        |  ${ammonite.Main.extraPredefString}
        |  import ammonite.repl.ReplBridge.{value => repl}
        |  import ammonite.interp.InterpBridge.{value => interp}
        |  import $wrapName._
      """.stripMargin +
      Preprocessor.CodeWrapper.bottom(printCode, indexedWrapperName, extraCode)
    }
  }
}

/**
  * Class that wraps each Mill build file.
  */
trait MainWrapper[T]{
  val discovered: mill.discover.Discovered[T]
  val interpApi: ammonite.interp.InterpAPI
  val mapping = discovered.mapping(this.asInstanceOf[T])

  mill.Main.consistencyCheck(mapping).left.foreach(msg => throw new Exception(msg))

  @ammonite.main.Router.main
  def run(args: String*) = mill.Main(
    args,
    mapping,
    interpApi.watch,
    true
  )


  val evaluator = new mill.eval.Evaluator(
    ammonite.ops.pwd / 'out,
    mapping.value,
    new mill.util.PrintLogger(true)
  )

  implicit val replApplyHandler: mill.main.ReplApplyHandler =
    new mill.main.ReplApplyHandler(evaluator)
}