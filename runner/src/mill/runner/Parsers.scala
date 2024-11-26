package mill.runner

import mill.api.internal
import mill.util.Util.newLine

import scala.collection.mutable
import mill.runner.worker.api.{MillScalaParser, ImportTree, ObjectData, Snip}

/**
 * Fastparse parser that extends the Scalaparse parser to handle `build.mill` and
 * other script files, and also for subsequently parsing any magic import
 * statements into [[ImportTree]] structures for the [[MillBuildRootModule]] to use
 *
 * TODO: currently this is unused, perhaps we should keep it if we still allow setting
 * scalaVersion in mill-build/build.mill files to scala 2?
 */
@internal
object Scala2Parsers extends MillScalaParser { outer =>
  import fastparse._
  import ScalaWhitespace._
  import scalaparse.Scala._

  // def debugParsers: MillScalaParser = new:
  //   def parseImportHooksWithIndices(stmts: Seq[String]): Seq[(String, Seq[ImportTree])] =
  //     outer.parseImportHooksWithIndices(stmts)
  //   def splitScript(rawCode: String, fileName: String): Either[String, (Seq[String], Seq[String])] =
  //     val res = outer.splitScript(rawCode, fileName)
  //     res.flatMap(success =>
  //       Left(s"Debug: (actually successful): $success")
  //     )

  def ImportSplitter[$: P]: P[Seq[ImportTree]] = {
    def IdParser = P((Id | Underscore).!).map(s => if (s(0) == '`') s.drop(1).dropRight(1) else s)
    def Selector: P[(String, Option[String])] = P(IdParser ~ (`=>` ~/ IdParser).?)
    def Selectors = P("{" ~/ Selector.rep(sep = ","./) ~ "}")
    def BulkImport = P(Underscore).map(_ => Seq("_" -> None))
    def Prefix = P((IdParser ~ Index).rep(1, sep = "."))
    def Suffix = P("." ~/ (BulkImport | Selectors))
    def ImportExpr: P[ImportTree] = {
      // Manually use `WL0` parser here, instead of relying on WhitespaceApi, as
      // we do not want the whitespace to be consumed even if the WL0 parser parses
      // to the end of the input (which is the default behavior for WhitespaceApi)
      P(Index ~~ Prefix ~~ (WL0 ~~ Suffix).? ~~ Index).map {
        case (start, idSeq, selectors, end) =>
          selectors match {
            case Some(selectors) => ImportTree(idSeq, selectors, start, end)
            case None => ImportTree(idSeq.init, Seq(idSeq.last._1 -> None), start, end)
          }
      }
    }
    P(`import` ~/ ImportExpr.rep(1, sep = ","./))
  }

  def Prelude[$: P]: P[Unit] = P((Annot ~ OneNLMax).rep ~ (Mod ~/ Pass).rep)

  def TmplStat[$: P]: P[Unit] = P(Import | Prelude ~ BlockDef | StatCtx.Expr)

  def HashBang[$: P]: P[Unit] = P(Start ~~ "#!" ~~ CharsWhile(_ != '\n') ~~ "\n")
  // Do this funny ~~WS thing to make sure we capture the whitespace
  // together with each statement; otherwise, by default, it gets discarded.
  //
  // After each statement, there must either be `Semis`, a "}" marking the
  // end of the block, or the `End` of the input
  def StatementBlock[$: P]: P[Seq[String]] =
    P(Semis.? ~ (TmplStat ~~ WS ~~ (Semis | &("}") | End)).!.repX)

  def TopPkgSeq[$: P]: P[Seq[String]] =
    P(((scalaparse.Scala.`package` ~ QualId.!) ~~ !(WS ~ "{")).repX(1, Semis))

  def CompilationUnit[$: P]: P[(Option[Seq[String]], String, Seq[String])] =
    P(Semis.? ~ TopPkgSeq.? ~~ WL.! ~~ StatementBlock ~ WL ~ End)

  def parseImportHooksWithIndices(stmts: Seq[String]): Seq[(String, Seq[ImportTree])] = {
    val hookedStmts = mutable.Buffer.empty[(String, Seq[ImportTree])]
    for (stmt <- stmts) {
      // Call `fastparse.ParserInput.fromString` explicitly, to avoid generating a
      // lambda in the class body and making the we-do-not-load-fastparse-on-cached-scripts
      // test fail
      parse(fastparse.ParserInput.fromString(stmt), ImportSplitter(using _)) match {
        case f: Parsed.Failure => hookedStmts.append((stmt, Nil))
        case Parsed.Success(parsedTrees, _) =>
          val importTrees = mutable.Buffer.empty[ImportTree]
          for (importTree <- parsedTrees) {
            importTree.prefix match {
              case Seq((s"$$$rest", _), _*) => importTrees.append(importTree)
              case _ => // do nothing
            }
          }
          hookedStmts.append((stmt, importTrees.toSeq))
      }
    }
    hookedStmts.toSeq
  }
  def formatFastparseError(fileName: String, rawCode: String, f: Parsed.Failure): String = {

    val lineColIndex = f.extra.input.prettyIndex(f.index)
    val expected = f.trace().failure.label
    val locationString = {
      val (first, last) = rawCode.splitAt(f.index)
      val lastSnippet = last.split(newLine).headOption.getOrElse("")
      val firstSnippet = first.reverse
        .split(newLine.reverse)
        .lift(0).getOrElse("").reverse
      firstSnippet + lastSnippet + newLine + (" " * firstSnippet.length) + "^"
    }
    s"$fileName:$lineColIndex expected $expected$newLine$locationString"
  }

  /**
   * Splits up a script file into its constituent blocks, each of which
   * is a tuple of (leading-whitespace, statements). Leading whitespace
   * is returned separately so we can later manipulate the statements e.g.
   * by adding `val res2 = ` without the whitespace getting in the way
   */
  def splitScript(rawCode: String, fileName: String): Either[String, (Seq[String], Seq[String])] = {
    parse(rawCode, CompilationUnit(using _)) match {
      case f: Parsed.Failure => Left(formatFastparseError(fileName, rawCode, f))
      case s: Parsed.Success[(Option[Seq[String]], String, Seq[String])] =>
        Right(s.value._1.toSeq.flatten -> (Seq(s.value._2) ++ s.value._3))
    }
  }

  override def parseObjectData(rawCode: String): Seq[ObjectData] = {
    val instrument = new ObjectDataInstrument(rawCode)
    // ignore errors in parsing, the file was already parsed successfully in `splitScript`
    val _ = fastparse.parse(rawCode, CompilationUnit(using _), instrument = instrument)
    instrument.objectData.toSeq
  }

  private case class Snippet(var text: String | Null = null, var start: Int = -1, var end: Int = -1)
      extends Snip

  private case class ObjectDataImpl(obj: Snippet, name: Snippet, parent: Snippet)
      extends ObjectData {
    def endMarker: Option[Snip] = None
    def finalStat: Option[(String, Snip)] = None // in scala 2 we do not need to inject anything
  }

  // Use Fastparse's Instrument API to identify top-level `object`s during a parse
  // and fish out the start/end indices and text for parts of the code that we need
  // to mangle and replace
  private class ObjectDataInstrument(scriptCode: String) extends fastparse.internal.Instrument {
    val objectData: mutable.Buffer[ObjectDataImpl] = mutable.Buffer.empty[ObjectDataImpl]
    val current: mutable.ArrayDeque[(String, Int)] = collection.mutable.ArrayDeque[(String, Int)]()
    def matches(stack: String*)(t: => Unit): Unit = if (current.map(_._1) == stack) { t }
    def beforeParse(parser: String, index: Int): Unit = {
      current.append((parser, index))
      matches("CompilationUnit", "StatementBlock", "TmplStat", "BlockDef", "ObjDef") {
        objectData.append(ObjectDataImpl(Snippet(), Snippet(), Snippet()))
      }
    }
    def afterParse(parser: String, index: Int, success: Boolean): Unit = {
      if (success) {
        def saveSnippet(s: Snippet) = {
          s.text = scriptCode.slice(current.last._2, index)
          s.start = current.last._2
          s.end = index
        }
        matches("CompilationUnit", "StatementBlock", "TmplStat", "BlockDef", "ObjDef", "`object`") {
          saveSnippet(objectData.last.obj)
        }
        matches("CompilationUnit", "StatementBlock", "TmplStat", "BlockDef", "ObjDef", "Id") {
          saveSnippet(objectData.last.name)
        }
        matches(
          "CompilationUnit",
          "StatementBlock",
          "TmplStat",
          "BlockDef",
          "ObjDef",
          "DefTmpl",
          "AnonTmpl",
          "NamedTmpl",
          "Constrs",
          "Constr",
          "AnnotType",
          "SimpleType",
          "BasicType",
          "TypeId",
          "StableId",
          "IdPath",
          "Id"
        ) {
          if (objectData.last.parent.text == null) saveSnippet(objectData.last.parent)
        }
      } else {
        matches("CompilationUnit", "StatementBlock", "TmplStat", "BlockDef", "ObjDef") {
          objectData.remove(objectData.length - 1)
        }
      }

      current.removeLast()
    }
  }
}
