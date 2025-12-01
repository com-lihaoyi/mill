package mill.daemon

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.Driver
import dotty.tools.dotc.ast.Positioned
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Contexts.ctx
import dotty.tools.dotc.core.Contexts.inContext
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.parsing.Parsers
import dotty.tools.dotc.parsing.Parsers.OutlineParser
import dotty.tools.dotc.parsing.Scanners.Scanner
import dotty.tools.dotc.parsing.Tokens
import dotty.tools.dotc.report
import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.dotc.reporting.ErrorMessageID
import dotty.tools.dotc.reporting.HideNonSensicalMessages
import dotty.tools.dotc.reporting.Message
import dotty.tools.dotc.reporting.MessageKind
import dotty.tools.dotc.reporting.MessageRendering
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.dotc.reporting.UniqueMessagePositions
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.dotc.util.SourcePosition

import mill.api.daemon.internal.MillScalaParser
import mill.api.daemon.internal.MillScalaParser.{ObjectData, Snip}

object MillScalaParserImpl extends MillScalaParser {

  def splitScript(
      rawCode: String,
      fileName: String,
      colored: Boolean
  ): Either[String, (String, Seq[String], Seq[String])] = {
    val source = SourceFile.virtual(fileName, rawCode)
    def mergeErrors(errors: List[String]): String =
      System.lineSeparator + errors.mkString(System.lineSeparator)
    splitScriptSource(source, colored).left.map(mergeErrors)
  }

  def splitScriptSource(
      source: SourceFile,
      colored: Boolean
  ): Either[List[String], (String, Seq[String], Seq[String])] = MillDriver.unitContext(source) {
    for
      trees <- liftErrors(colored, MillParsers.outlineCompilationUnit(source))
      (pkgs, stmts) <- liftErrors(colored, splitTrees(trees))
    yield {
      val prefix = trees match {
        case untpd.PackageDef(untpd.Ident(nme.EMPTY_PACKAGE), _) :: Nil =>
          // no package directive in build file, YAML header should already be in stmts
          ""
        case _ =>
          if (!trees.head.startPos.exists) ""
          else new String(source.file.toByteArray).take(trees.head.startPos.start)
      }

      (prefix, pkgs, stmts)
    }
  }

  def liftErrors[T](colored: Boolean, op: Context ?=> T)(using Context): Either[List[String], T] = {
    val res = op
    if ctx.reporter.hasErrors then
      Left(MillDriver.renderErrors(colored: Boolean))
    else
      Right(res)
  }

  def parseObjectData(rawCode: String): Seq[ObjectData] = {
    parseObjects(SourceFile.virtual("<script>", rawCode))
  }

  def parseObjects(source: SourceFile): Seq[ObjectData] = MillDriver.unitContext(source) {
    val trees = MillParsers.outlineCompilationUnit(source)
    // syntax was already checked in splitScript, so any errors would suggest a bug
    assert(!ctx.reporter.hasErrors, "valid script parsing should not have errors.")
    objectDatas(trees)
  }

  private case class Snippet(text: String | Null = null, start: Int = -1, end: Int = -1)
      extends Snip

  private case class ObjectDataImpl(
      obj: Snippet,
      name: Snippet,
      parent: Snippet,
      endMarker: Option[Snippet],
      finalStat: Option[(String, Snippet)]
  ) extends ObjectData

  /** MillParsers contains the code for parsing objects and imports from text. */
  private object MillParsers {

    /** Dotty parsers need to be synchronized */
    private object ParseLock extends java.util.concurrent.locks.ReentrantLock {

      /** Synchronize the operation `op` */
      inline def sync[T](inline op: T): T = {
        try {
          lock()
          op
        } finally {
          unlock()
        }
      }
    }

    trait MillParserCommon extends Parsers.Parser {
      override def atSpan[T <: Positioned](span: Span)(t: T): T = {
        if t == untpd.EmptyTree || t == untpd.EmptyValDef then t
        else super.atSpan(span)(t)
      }
    }

    def outlineCompilationUnit(source: SourceFile)(using Context): List[untpd.Tree] = {
      ParseLock.sync {
        val parser = new OutlineParser(source) with MillParserCommon {

          /**
           * This is an outline parser, so will skip template bodies anyway,
           * however in our override of `topStatSeq` we redirect to `templateStatSeq`,
           * which expects an initial self-type. Mill scripts do not have self-types.
           * By immediately returning an empty `ValDef` then the parser will not
           * consume any user-written top-level self type, and instead emit
           * the expected syntax error.
           */
          override def selfType(): untpd.ValDef =
            untpd.EmptyValDef

          /**
           * A Mill compilation unit is effectively a package declaration followed by statements
           * that will be spliced into a template body.
           * So we can emulate this by parsing a standard compilation unit - so reading the outer packages,
           * and then as soon as we would drop down to "top-level" statements we then switch to
           * parsing the body of the `RootModule` object, but we should not allow a self-type.
           */
          override def topStatSeq(outermost: Boolean): List[untpd.Tree] = {
            val (_, stats) = templateStatSeq()
            stats
          }
        }

        parser.parse() match {
          case untpd.Thicket(trees) => Trees.flatten(trees)
          case tree => tree :: Nil
        }
      }
    }

    def importStatement(source: SourceFile)(using Context): List[untpd.Tree] = ParseLock.sync {
      val parser = new OutlineParser(source) with MillParserCommon {}
      if parser.in.token == Tokens.IMPORT then
        parser.importClause()
      else
        Nil
    }

    def nextTokenIsntBlock(offset: Int)(using Context): Boolean = ParseLock.sync {
      val in = Scanner(ctx.source, startFrom = offset)
      val token = in.token
      token != Tokens.COLONop && token != Tokens.LBRACE
    }

    def nextTokenIsntImport(offset: Int)(using Context): Boolean = ParseLock.sync {
      val in = Scanner(ctx.source, startFrom = offset)
      val token = in.token
      token != Tokens.IMPORT
    }

    /** read the offset of the `object` keyword in a Module declaration */
    def skipModsObjectOffset(offset: Int)(using Context): Int = ParseLock.sync {
      val in0 = Scanner(ctx.source, startFrom = offset)
      val parser = new OutlineParser(ctx.source) with MillParserCommon {
        override val in = in0
      }
      val _ = parser.defAnnotsMods(Tokens.modifierTokens)
      if (in0.token == Tokens.OBJECT) in0.offset
      else -1
    }

    /** read the statements of a template body */
    def outlineTemplateBody(offset: Int)(using Context): List[untpd.Tree] =
      Trees.flatten(outlineTemplate(offset).body)

    private def outlineTemplate(offset: Int)(using Context): untpd.Template = ParseLock.sync {
      val in0 = Scanner(ctx.source, startFrom = offset)

      val outlineParser: OutlineParser = new OutlineParser(ctx.source) with MillParserCommon {
        override val in = in0
      }

      // parser that will enter a template body, but then not parse nested templates
      val parser = new Parsers.Parser(ctx.source) with MillParserCommon {
        override val in = in0

        override def templateStatSeq(): (untpd.ValDef, List[untpd.Tree]) =
          outlineParser.templateStatSeq()
      }
      parser.templateOpt(untpd.emptyConstructor)
    }
  }

  private def syntaxError(msg0: String)(using Context): Message = {
    new Message(ErrorMessageID.NoExplanationID) {
      override def kind: MessageKind = MessageKind.Syntax
      override protected def msg(using Context): String = msg0
      override protected def explain(using Context): String = ""
    }
  }

  private def objectDatas(trees: List[untpd.Tree])(using Context): Seq[ObjectDataImpl] = {
    val buf = Seq.newBuilder[ObjectDataImpl]
    val content = ctx.source.content()

    def moduleDef(mdef: untpd.ModuleDef): Option[ObjectDataImpl] = {
      val untpd.ModuleDef(name, impl) = mdef
      val obj0 = {
        val start0 = MillParsers.skipModsObjectOffset(mdef.sourcePos.start)
        if (start0 == -1) None
        else {
          val end0 = start0 + "object".length()
          val text = slice(start0, end0)
          assert(text == "object", s"expected `object`, actually was `$text`")
          Some(Snippet(text, start0, end0))
        }
      }
      val (name0, expanded) = {
        val start0 = mdef.sourcePos.point
        val nameStr = name.show
        val end0 = start0 + nameStr.length
        val backTickedBefore = content.isDefinedAt(start0 - 1) && content(start0 - 1) == '`'
        if backTickedBefore && content.isDefinedAt(end0)
        then {
          val start1 = start0 - 1
          val end1 = end0 + 1
          val text = slice(start1, end1)
          assert(text == s"`$nameStr`", s"expected {`$nameStr`}, actually was {$text}")
          Snippet(text, start1, end1) -> true
        } else {
          val text = slice(start0, end0)
          assert(text == nameStr, s"expected {$nameStr}, actually was {$text}")
          Snippet(text, start0, end0) -> false
        }
      }
      val parent0 = {
        impl.parents match {
          case parent :: _ if validSpan(parent.sourcePos) =>
            val start0 = parent.sourcePos.start
            val end0 = parent.sourcePos.end
            val text = slice(start0, end0)
            Snippet(text, start0, end0)
          case _ =>
            Snippet()
        }
      }
      val endMarker0 = {
        val endSpan = mdef.endSpan
        if endSpan.exists then {
          // Dotty bug - "end `package`" span is off by two characters
          // i.e. it is computed by subtracting the name length from the end of span,
          // ignoring backticks
          val start0 = endSpan.start + (if expanded then -2 else 0)
          val end0 = endSpan.end
          val text = slice(start0, end0)
          Some(Snippet(text, start0, end0))
        } else {
          None
        }
      }
      val finalStat0 = {
        // find the whitespace before the first statement in the object
        val body =
          MillParsers.outlineTemplateBody(name0.end).filter(stat => validSpan(stat.sourcePos))
        val leading0 = body.headOption.map({ stat =>
          val start0 = ctx.source.startOfLine(stat.sourcePos.start)
          val end0 = stat.sourcePos.start
          val leading = slice(start0, end0)
          leading
        })
        val stat0 = body.lastOption.map(stat => {
          val start0 = stat.sourcePos.end
          val end0 = endMarker0.map(_.start).getOrElse(stat.sourcePos.end)
          val text = slice(start0, end0)
          Snippet(text, start0, end0)
        })
        for {
          leading <- leading0
          stat <- stat0
        } yield (leading, stat)
      }

      obj0.map(ObjectDataImpl(_, name0, parent0, endMarker0, finalStat0))
    }

    def topLevel(trees: List[untpd.Tree]): Unit = trees match {
      case (mdef @ untpd.ModuleDef(_, _)) :: trees1 if validSpan(mdef.sourcePos) =>
        for obj <- moduleDef(mdef) do
          buf += obj
        topLevel(trees1)
      case _ :: trees1 =>
        topLevel(trees1)
      case Nil =>
        ()
    }

    def compilationUnit(trees: List[untpd.Tree]): Unit = trees match {
      case untpd.PackageDef(_, stats) :: Nil =>
        compilationUnit(stats)
      case _ =>
        topLevel(trees)
    }

    compilationUnit(trees)

    buf.result()
  }

  private def validSpan(sourcePos: SourcePosition): Boolean =
    sourcePos.span.exists && !sourcePos.span.isSynthetic

  private def slice(start: Int, end: Int)(using Context): String =
    ctx.source.content.slice(start, end).mkString

  private def splitTrees(trees: List[untpd.Tree])(using Context): (Seq[String], Seq[String]) = {
    val topLevelPkgs = Seq.newBuilder[String]
    val topLevelStats = Seq.newBuilder[String]
    val initialStats = trees match {
      case untpd.PackageDef(untpd.Ident(nme.EMPTY_PACKAGE), stats) :: Nil =>
        // dotty will always insert a package `<empty>` if there is no package declaration,
        // or if there is an import before the package declaration.
        // we should ignore this package
        Trees.flatten(stats) // could be EmptyTree
      case _ =>
        trees // don't unwrap the package
    }

    def commaSeparatedImport(tree: untpd.Import): Boolean = {
      val pos = tree.sourcePos
      validSpan(pos) && MillParsers.nextTokenIsntImport(pos.start)
    }

    /** We need to pack consecutive imports where the following is synthetic */
    def importRest(start: Int, from: Int, trees: List[untpd.Tree]): Unit = trees match {
      case (tree @ untpd.Import(_, _)) :: trees1 if commaSeparatedImport(tree) =>
        // import comes from a comma separated list of imports
        importRest(start, tree.sourcePos.end, trees1)
      case _ =>
        // next statement wasnt a synthetic import, collect the imports
        topLevelStats += slice(start, from)
        topLevel(from, trees)
    }

    def topLevel(from: Int, trees0: List[untpd.Tree]): Unit = trees0 match {
      case tree :: trees1 =>
        val pos = tree.sourcePos
        if validSpan(pos) then {
          topLevelStats += slice(from, pos.start)
          tree match {
            case untpd.Import(_, _) =>
              importRest(pos.start, pos.end, trees1)
            case _ =>
              topLevelStats += slice(pos.start, pos.end)
              topLevel(pos.end, trees1)
          }
        } else {
          // TODO: let's check if this actually happens, if so, perhaps we should just ignore it
          // i.e. if it's generated code, then nothing is lost by ignoring it.
          report.error(syntaxError(s"unexpected tree ${tree.show}."), pos)
          topLevel(from, trees1)
        }
      case Nil => ()
    }

    def literalPackageId(pre: untpd.Tree, acc: List[String]): String = {
      pre match {
        case id @ untpd.Ident(_) =>
          val acc1 = slice(id.sourcePos.start, id.sourcePos.end) :: acc
          acc1.mkString(".")
        case sel @ untpd.Select(qual, _) =>
          val acc1 = slice(sel.sourcePos.point, sel.sourcePos.end) :: acc
          literalPackageId(qual, acc1)
      }
    }

    def compilationUnit(from: Int, trees: List[untpd.Tree]): Unit = {
      trees match {
        case untpd.PackageDef(pid, stats) :: Nil if validSpan(pid.sourcePos) =>
          val end0 = pid.sourcePos.end
          if MillParsers.nextTokenIsntBlock(end0) then {
            topLevelPkgs += literalPackageId(pid, Nil)
            compilationUnit(end0, stats)
          } else {
            report.error(syntaxError(s"Mill forbids packages to introduce a block."), pid.sourcePos)
          }
        case _ =>
          topLevel(from, trees)
      }
    }

    compilationUnit(0, initialStats)

    (topLevelPkgs.result(), topLevelStats.result())
  }

  /** The MillDriver contains code for initializing a Context and reporting errors. */
  private object MillDriver extends Driver {

    private object MillRendering extends MessageRendering

    def renderErrors(colored: Boolean)(using Context): List[String] = {
      val errs = ctx.reporter.removeBufferedMessages.collect {
        case err: Diagnostic.Error => err
      }

      val shade: java.util.function.Function[String, String] =
        if (colored) msg => Console.RED + msg + Console.RESET
        else msg => msg

      errs.map { d =>
        val pos = d.pos
        if !pos.exists then d.msg.message
        else {
          // Scrape colored line content from Dotty's rendered output
          val renderedLines = MillRendering.messageAndPos(d).linesIterator.toSeq
          val lineContent = mill.api.internal.Util.scrapeColoredLineContent(
            renderedLines,
            pos.lineContent.stripLineEnd
          )

          val pointerLength =
            if pos.span.exists then math.max(1, pos.endColumn - pos.column)
            else 1

          mill.constants.Util.formatError(
            pos.source.file.path,
            pos.line + 1,
            pos.column + 1,
            lineContent,
            d.msg.message,
            pointerLength,
            shade
          )
        }
      }
    }

    private final class MillStoredReporter extends StoreReporter(null) with UniqueMessagePositions
        with HideNonSensicalMessages {
      def dropAll(): Unit = infos = null
    }

    def unitContext[T](source: SourceFile)(op: Context ?=> T): T = {
      val reporter = MillStoredReporter()
      try {
        val ictx = initCtx.fresh
        val unitCtx = inContext(ictx) {
          val unit = CompilationUnit(source, mustExist = false)
          ictx.setReporter(reporter).setCompilationUnit(unit)
        }
        inContext(unitCtx)(op)
      } finally {
        reporter.dropAll()
      }
    }
  }

}
