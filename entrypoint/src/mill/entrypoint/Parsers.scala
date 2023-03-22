package mill.entrypoint

import mill.util.Util.{newLine, windowsPlatform}

import scala.collection.mutable

case class ImportTree(prefix: Seq[String],
                      mappings: Option[ImportTree.ImportMapping],
                      start: Int,
                      end: Int)

object ImportTree{
  type ImportMapping = Seq[(String, Option[String])]
}

object Parsers  {

  import fastparse._

  import ScalaWhitespace._
  import scalaparse.Scala._

  // For some reason Scala doesn't import this by default
  private def `_`[$: P] = scalaparse.Scala.Underscore


  private def ImportSplitter[$: P]: P[Seq[ImportTree]] = {
    def IdParser = P( (Id | `_` ).! ).map(
      s => if (s(0) == '`') s.drop(1).dropRight(1) else s
    )
    def Selector = P( IdParser ~ (`=>` ~/ IdParser).? )
    def Selectors = P( "{" ~/ Selector.rep(sep = ","./) ~ "}" )
    def BulkImport = P( `_`).map(
      _ => Seq("_" -> None)
    )
    def Prefix = P( IdParser.rep(1, sep = ".") )
    def Suffix = P( "." ~/ (BulkImport | Selectors) )
    def ImportExpr: P[ImportTree] = {
      // Manually use `WL0` parser here, instead of relying on WhitespaceApi, as
      // we do not want the whitespace to be consumed even if the WL0 parser parses
      // to the end of the input (which is the default behavior for WhitespaceApi)
      P( Index ~~ Prefix ~~ (WL0 ~~ Suffix).? ~~ Index).map{
        case (start, idSeq, selectors, end) =>
          ImportTree(idSeq, selectors, start, end)
      }
    }
    P( `import` ~/ ImportExpr.rep(1, sep = ","./) )
  }

  private def Prelude[$: P] = P( (Annot ~ OneNLMax).rep ~ (Mod ~/ Pass).rep )

  private def TmplStat[$: P] = P( Import | Prelude ~ BlockDef | StatCtx.Expr )


  // Do this funny ~~WS thing to make sure we capture the whitespace
  // together with each statement; otherwise, by default, it gets discarded.
  //
  // After each statement, there must either be `Semis`, a "}" marking the
  // end of the block, or the `End` of the input
  private def StatementBlock[$: P] =
    P( Semis.? ~ (TmplStat ~~ WS ~~ (Semis | &("}") | End)).!.repX)


  private def CompilationUnit[$: P] = P( WL.! ~ StatementBlock ~ WL )

  def stringWrap(s: String): String = "\"" + pprint.Util.literalize(s) + "\""
  def stringSymWrap(s: String): String = {
    def idToEnd[$: P] = P( scalaparse.syntax.Identifiers.Id ~ End )
    if (s == "") "'"
    else parse(s, idToEnd(_))  match{
      case Parsed.Success(v, _) =>  "'" + s
      case f: Parsed.Failure => stringWrap(s)
    }
  }
  def parseImportHooksWithIndices(stmts: Seq[String]): (Seq[String], Seq[ImportTree]) = {
    val hookedStmts = mutable.Buffer.empty[String]
    val importTrees = mutable.Buffer.empty[ImportTree]
    for(stmt <- stmts) {
      // Call `fastparse.ParserInput.fromString` explicitly, to avoid generating a
      // lambda in the class body and making the we-do-not-load-fastparse-on-cached-scripts
      // test fail
      parse(fastparse.ParserInput.fromString(stmt), ImportSplitter(_)) match{
        case f: Parsed.Failure => hookedStmts.append(stmt)
        case Parsed.Success(parsedTrees, _) =>
          var currentStmt = stmt
          for(importTree <- parsedTrees){
            if (importTree.prefix(0)(0) == '$') {
              val length = importTree.end - importTree.start
              currentStmt = currentStmt.patch(
                importTree.start, "_root_._".padTo(length, ' '), length
              )
              importTrees.append(importTree)
            }
          }
          hookedStmts.append(currentStmt)
      }
    }
    (hookedStmts.toSeq, importTrees.toSeq)
  }
  def formatFastparseError(fileName: String, rawCode: String, f: Parsed.Failure) = {

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
  def splitScript(rawCode: String,
                  fileName: String): Either[String, Seq[String]] = {
    parse(rawCode, CompilationUnit(_)) match {
      case f: Parsed.Failure =>
        Left(formatFastparseError(fileName, rawCode, f))

      case s: Parsed.Success[(String, Seq[String])] =>


        Right(Seq(s.value._1 + s.value._2.head) ++ s.value._2.tail)
    }
  }

  case class ScriptBlock(startIndex: Int,
                         ncomment: String,
                         codeWithStartIndices: Seq[(Int, String)])
}
