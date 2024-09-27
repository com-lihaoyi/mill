package mill.runner.worker

import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.Compiler
import dotty.tools.dotc.Driver
import dotty.tools.dotc.ast.Positioned
import dotty.tools.dotc.ast.Trees
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.ast.untpd.ImportSelector
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Contexts.ctx
import dotty.tools.dotc.core.Contexts.inContext
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.core.StdNames.nme
import dotty.tools.dotc.interfaces
import dotty.tools.dotc.parsing.Parser
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
import dotty.tools.dotc.reporting.Profile
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.dotc.reporting.UniqueMessagePositions
import dotty.tools.dotc.util.Property
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.Spans.Span
import mill.runner.worker.api.ImportTree
import mill.runner.worker.api.ObjectData
import mill.runner.worker.api.ScalaCompilerWorkerApi
import mill.runner.worker.api.Snip

import java.io.File
import java.net.URLClassLoader
import scala.concurrent.duration.span
import dotty.tools.dotc.util.SourcePosition

final class ScalaCompilerWorkerImpl extends ScalaCompilerWorkerApi { worker =>

  def splitScript(rawCode: String, fileName: String): Either[String, (Seq[String], Seq[String])] = {
    val source = SourceFile.virtual(fileName, rawCode)
    splitScriptSource(source).left.map(_.mkString("\n"))
  }

  def splitScriptSource(
      source: SourceFile
  ): Either[List[String], (Seq[String], Seq[String])] = MillDriver.unitContext(source) {
    for
      trees <- liftErrors(MillParsers.outlineCompilationUnit(source))
      split <- liftErrors(splitTrees(trees))
    yield split
  }

  def liftErrors[T](op: Context ?=> T)(using Context): Either[List[String], T] =
    val res = op
    if ctx.reporter.hasErrors then
      Left(MillDriver.renderErrors())
    else
      Right(res)

  def parseImportHooksWithIndices(stmts: Seq[String]): Seq[(String, Seq[ImportTree])] = {
    for stmt <- stmts yield
      val imports = {
        if stmt.startsWith("import") then
          parseImportTrees(SourceFile.virtual("<import>", stmt))
        else
          Nil
      }
      (stmt, imports)
    end for
  }

  def parseImportTrees(source: SourceFile): Seq[ImportTree] = MillDriver.unitContext(source) {
    val trees = MillParsers.importStatement(source)
    // syntax was already checked in splitScript, so any errors would suggest a bug
    assert(!ctx.reporter.hasErrors, "Import parsing should not have errors.")
    importTrees(trees)
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

    def outlineCompilationUnit(source: SourceFile)(using Context): List[untpd.Tree] =
      ParseLock.sync {
        val parser = new OutlineParser(source) {

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
          override def topStatSeq(outermost: Boolean): List[untpd.Tree] =
            val (_, stats) = templateStatSeq()
            stats
        }

        parser.parse() match
          case untpd.Thicket(trees) => Trees.flatten(trees)
          case tree => tree :: Nil
      }

    def importStatement(source: SourceFile)(using Context): List[untpd.Tree] = ParseLock.sync {
      val parser = OutlineParser(source)
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
      val parser = new OutlineParser(ctx.source) {
        override val in = in0
      }
      val _ = parser.defAnnotsMods(Tokens.modifierTokens)
      assert(in0.token == Tokens.OBJECT, s"Expected `object`, got ${in0.token}")
      in0.offset
    }

    /** read the statements of a template body */
    def outlineTemplateBody(offset: Int)(using Context): List[untpd.Tree] =
      Trees.flatten(outlineTemplate(offset).body)

    private def outlineTemplate(offset: Int)(using Context): untpd.Template = ParseLock.sync {
      val in0 = Scanner(ctx.source, startFrom = offset)

      val outlineParser: OutlineParser = new OutlineParser(ctx.source) {
        override val in = in0

        override def atSpan[T <: Positioned](span: Span)(t: T): T =
          if t == untpd.EmptyTree || t == untpd.EmptyValDef then t
          else super.atSpan(span)(t)
      }

      // parser that will enter a template body, but then not parse nested templates
      val parser = new Parsers.Parser(ctx.source) {
        override val in = in0

        override def atSpan[T <: Positioned](span: Span)(t: T): T =
          if t == untpd.EmptyTree || t == untpd.EmptyValDef then t
          else super.atSpan(span)(t)

        override def templateStatSeq(): (untpd.ValDef, List[untpd.Tree]) =
          outlineParser.templateStatSeq()
      }
      parser.templateOpt(untpd.emptyConstructor)
    }
  }

  private def syntaxError(msg0: String)(using Context): Message =
    new Message(ErrorMessageID.NoExplanationID) {
      override def kind: MessageKind = MessageKind.Syntax
      override protected def msg(using Context): String = msg0
      override protected def explain(using Context): String = ""
    }

  private def objectDatas(trees: List[untpd.Tree])(using Context): Seq[ObjectDataImpl] = {
    val buf = Seq.newBuilder[ObjectDataImpl]
    val content = ctx.source.content()

    def moduleDef(mdef: untpd.ModuleDef): Option[ObjectDataImpl] = {
      val untpd.ModuleDef(name, impl) = mdef
      val obj0 = {
        val start0 = MillParsers.skipModsObjectOffset(mdef.sourcePos.start)
        val end0 = start0 + "object".length()
        val text = slice(start0, end0)
        assert(text == "object", s"expected `object`, actually was `$text`")
        Snippet(text, start0, end0)
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
        impl.parents match
          case parent :: _ if validSpan(parent.sourcePos) =>
            val start0 = parent.sourcePos.start
            val end0 = parent.sourcePos.end
            val text = slice(start0, end0)
            Snippet(text, start0, end0)
          case _ =>
            Snippet()
      }
      val endMarker0 = {
        val endSpan = mdef.endSpan
        if endSpan.exists then
          // Dotty bug - "end `package`" span is off by two characters
          // i.e. it is computed by subtracting the name length from the end of span,
          // ignoring backticks
          val start0 = endSpan.start + (if expanded then -2 else 0)
          val end0 = endSpan.end
          val text = slice(start0, end0)
          Some(Snippet(text, start0, end0))
        else
          None
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
      val obj = ObjectDataImpl(obj0, name0, parent0, endMarker0, finalStat0)
      Some(obj)
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

  private def importTrees(trees: List[untpd.Tree])(using Context): Seq[ImportTree] = {
    val buf = Seq.newBuilder[ImportTree]

    def prefixAsSeq(pre: untpd.Tree, acc: List[(String, Int)]): Option[(Int, Seq[(String, Int)])] =
      pre match {
        case untpd.Ident(name) =>
          // the innermost part of an import prefix is always an `Ident`
          name.show match
            case millName @ s"$$$_" =>
              Some(pre.sourcePos.start -> ((millName -> pre.sourcePos.end) :: acc))
            case _ => None // first index wasn't a $foo import, so ignore
        case untpd.Select(qual, name) =>
          // i.e. `Select(qual, name)` === `qual.name`
          prefixAsSeq(qual, (name.show -> pre.sourcePos.end) :: acc)
      }

    def importTree(tree: untpd.Import): Option[ImportTree] = {
      val untpd.Import(prefix, sels) = tree
      for
        (start, prefix1) <- prefixAsSeq(prefix, Nil)
      yield {
        val sels1 = sels.map {
          case ImportSelector(src, untpd.EmptyTree, untpd.EmptyTree) =>
            (src.show, None)
          case ImportSelector(src, rename, untpd.EmptyTree) =>
            (src.show, Some(rename.show))
          case ImportSelector(_, untpd.EmptyTree, tpe) =>
            ("_", None) // (e.g. `import $file.foo.{given T}`)
        }
        ImportTree(prefix1, sels1, start, tree.sourcePos.end)
      }
    }

    def loop(trees: List[untpd.Tree]): Unit = trees match {
      case (head @ untpd.Import(_, _)) :: trees1 if validSpan(head.sourcePos) =>
        for tree <- importTree(head) do {
          buf += tree
        }
        loop(trees1)
      case tree :: trees1 =>
        loop(trees1)
      case Nil => ()
    }

    loop(trees)

    buf.result()
  }

  private def validSpan(sourcePos: SourcePosition)(using Context): Boolean =
    sourcePos.span.exists && !sourcePos.span.isSynthetic

  private def slice(start: Int, end: Int)(using Context): String =
    ctx.source.content.slice(start, end).mkString

  private def splitTrees(trees: List[untpd.Tree])(using Context): (Seq[String], Seq[String]) = {
    val content = ctx.source.content()
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

    def commaSeparatedImport(tree: untpd.Import): Boolean =
      val pos = tree.sourcePos
      validSpan(pos) && MillParsers.nextTokenIsntImport(pos.start)

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
        if validSpan(pos) then
          topLevelStats += slice(from, pos.start)
          tree match {
            case untpd.Import(_, _) =>
              importRest(pos.start, pos.end, trees1)
            case _ =>
              topLevelStats += slice(pos.start, pos.end)
              topLevel(pos.end, trees1)
          }
        else
          // TODO: let's check if this actually happens, if so, perhaps we should just ignore it
          // i.e. if it's generated code, then nothing is lost by ignoring it.
          report.error(syntaxError(s"unexpected tree ${tree.show}."), pos)
          topLevel(from, trees1)
      case Nil => ()
    }

    def literalPackageId(pre: untpd.Tree, acc: List[String]): String =
      pre match {
        case id @ untpd.Ident(_) =>
          val acc1 = slice(id.sourcePos.start, id.sourcePos.end) :: acc
          acc1.mkString(".")
        case sel @ untpd.Select(qual, _) =>
          val acc1 = slice(sel.sourcePos.point, sel.sourcePos.end) :: acc
          literalPackageId(qual, acc1)
      }

    def compilationUnit(from: Int, trees: List[untpd.Tree]): Unit = {
      trees match {
        case untpd.PackageDef(pid, stats) :: Nil if validSpan(pid.sourcePos) =>
          val end0 = pid.sourcePos.end
          if MillParsers.nextTokenIsntBlock(end0) then
            topLevelPkgs += literalPackageId(pid, Nil)
            compilationUnit(end0, stats)
          else
            report.error(syntaxError(s"Mill forbids packages to introduce a block."), pid.sourcePos)
        case _ =>
          topLevel(from, trees)
      }
    }

    compilationUnit(0, initialStats)

    (topLevelPkgs.result(), topLevelStats.result())
  }

  private object MillRendering extends MessageRendering {

    /**
     * Dotty has an off-by-one error with the column in rendering messages, fix it here
     * (there is regression testing for this in `ParseErrorTests.scala`)
     */
    override protected def posFileStr(pos: SourcePosition): String =
      val path = pos.source.file.path
      if pos.exists then s"$path:${pos.line + 1}:${pos.column + 1}" else path

    /** strip color from result */
    override def messageAndPos(dia: Diagnostic)(using Context): String =
      super.messageAndPos(dia).replaceAll("\u001B\\[[;\\d]*m", "")
  }

  /** The MillDriver contains code for initializing a Context and reporting errors. */
  private object MillDriver extends Driver {

    /** While just parsing it isn't necessary to refresh the context */
    override protected val initCtx: Context = super.initCtx

    def renderErrors()(using Context): List[String] = {
      val errs = ctx.reporter.removeBufferedMessages.collect {
        case err: Diagnostic.Error => err
      }
      errs.map(d =>
        MillRendering.messageAndPos(d)
      )
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
