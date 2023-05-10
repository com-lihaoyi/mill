package mill.runner

import mill.api.internal
import mill.util.Util.newLine

import scala.collection.mutable

@internal
case class ImportTree(
    prefix: Seq[(String, Int)],
    mappings: Seq[(String, Option[String])],
    start: Int,
    end: Int
)

/**
 * Fastparse parser that extends the Scalaparse parser to handle `build.sc` and
 * other script files, and also for subsequently parsing any magic import
 * statements into [[ImportTree]] structures for the [[MillBuildRootModule]] to use
 */
@internal
object Parsers {
  import fastparse._
  import ScalaWhitespace._
  import scalaparse.Scala._

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

  def Prelude[$: P] = P((Annot ~ OneNLMax).rep ~ (Mod ~/ Pass).rep)

  def TmplStat[$: P] = P(Import | Prelude ~ BlockDef | StatCtx.Expr)

  def HashBang[$: P] = P(Start ~~ "#!" ~~ CharsWhile(_ != '\n') ~~ "\n")
  // Do this funny ~~WS thing to make sure we capture the whitespace
  // together with each statement; otherwise, by default, it gets discarded.
  //
  // After each statement, there must either be `Semis`, a "}" marking the
  // end of the block, or the `End` of the input
  def StatementBlock[$: P] =
    P(Semis.? ~ (TmplStat ~~ WS ~~ (Semis | &("}") | End)).!.repX)

  def CompilationUnit[$: P] = P(HashBang.!.? ~ WL.! ~ StatementBlock ~ WL ~ End)

  def stringWrap(s: String): String = "\"" + pprint.Util.literalize(s) + "\""
  def stringSymWrap(s: String): String = {
    def idToEnd[$: P] = P(scalaparse.syntax.Identifiers.Id ~ End)
    if (s == "") "'"
    else parse(s, idToEnd(_)) match {
      case Parsed.Success(v, _) => "'" + s
      case f: Parsed.Failure => stringWrap(s)
    }
  }
  def parseImportHooksWithIndices(stmts: Seq[String]): Seq[(String, Seq[ImportTree])] = {
    val hookedStmts = mutable.Buffer.empty[(String, Seq[ImportTree])]
    for (stmt <- stmts) {
      // Call `fastparse.ParserInput.fromString` explicitly, to avoid generating a
      // lambda in the class body and making the we-do-not-load-fastparse-on-cached-scripts
      // test fail
      parse(fastparse.ParserInput.fromString(stmt), ImportSplitter(_)) match {
        case f: Parsed.Failure => hookedStmts.append((stmt, Nil))
        case Parsed.Success(parsedTrees, _) =>
          val importTrees = mutable.Buffer.empty[ImportTree]
          for (importTree <- parsedTrees) {
            if (importTree.prefix(0)._1(0) == '$') importTrees.append(importTree)
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
  def splitScript(rawCode: String, fileName: String): Either[String, Seq[String]] = {
    parse(rawCode, CompilationUnit(_)) match {
      case f: Parsed.Failure => Left(formatFastparseError(fileName, rawCode, f))
      case s: Parsed.Success[(Option[String], String, Seq[String])] =>
        Right(s.value._1.toSeq.map(_ => "\n") ++ Seq(s.value._2) ++ s.value._3)
    }
  }
}
