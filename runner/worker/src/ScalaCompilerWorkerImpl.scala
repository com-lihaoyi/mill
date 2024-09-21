package mill.runner.worker

import mill.runner.worker.api.{ScalaCompilerWorkerApi, ImportTree, Snip, ObjectData}

import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.Driver
import dotty.tools.dotc.Compiler
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Contexts.inContext
import dotty.tools.dotc.core.Contexts.ctx
import dotty.tools.dotc.core.Phases.Phase
import dotty.tools.dotc.parsing.Parser
import dotty.tools.dotc.reporting.StoreReporter
import dotty.tools.dotc.reporting.UniqueMessagePositions
import dotty.tools.dotc.reporting.HideNonSensicalMessages
import dotty.tools.dotc.reporting.Diagnostic
import dotty.tools.dotc.reporting.MessageKind
import dotty.tools.dotc.reporting.Profile
import dotty.tools.dotc.interfaces
import dotty.tools.dotc.core.StdNames.nme
import java.net.URLClassLoader
import java.io.File
import dotty.tools.dotc.report
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.util.Property
import dotty.tools.dotc.parsing.Parsers.OutlineParser
import dotty.tools.dotc.parsing.Parsers
import dotty.tools.dotc.parsing.Tokens
import dotty.tools.dotc.parsing.Scanners.Scanner
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.reporting.Message
import dotty.tools.dotc.reporting.ErrorMessageID
import dotty.tools.dotc.ast.untpd.ImportSelector
import dotty.tools.dotc.ast.Positioned
import scala.concurrent.duration.span
import dotty.tools.dotc.reporting.MessageRendering

final class ScalaCompilerWorkerImpl extends ScalaCompilerWorkerApi { worker =>

  // class TreesVisitor() {
  //   var trees: Map[String, (String, Seq[String], Seq[String])] = Map.empty

  //   override def toString(): String = {
  //     trees.map((src, triple) =>
  //       s"""-- $src --
  //          |${triple(0)}
  //          |----------
  //          |packages:
  //          |${triple(1).map(p => s"<{$p}>").mkString("\n")}
  //          |spacings:
  //          |${triple(2).map(p => s"<{$p}>").mkString("\n")}
  //          |----------""".stripMargin
  //     ).mkString("\n")
  //   }
  // }

  // /**
  //  * This class expects to be loaded via a URLClassLoader, or else the
  //  * compiler will have no classpath to work with.
  //  */
  // private val bootstrapClasspath: String | Null = {
  //   getClass().getClassLoader() match
  //     case classLoader: URLClassLoader =>
  //       classLoader.getURLs().map(_.getPath()).mkString(File.pathSeparator)
  //     case _ =>
  //       null
  // }

  def splitScript(rawCode: String, fileName: String): Either[String, (Seq[String], Seq[String])] = {
    val source = SourceFile.virtual(fileName, rawCode)
    MillDriver.parseSingle0(source).left.map(_.mkString("\n"))
  }

  def parseImportHooksWithIndices(stmts: Seq[String]): Seq[(String, Seq[ImportTree])] = {
    // sys.error(s"Not implemented:\n>  ${stmts.mkString("\n>  ")}")
    val msgs = List.newBuilder[String]
    val res =
      for stmt <- stmts yield
        val imports =
          try {
            if stmt.startsWith("import") then
              MillDriver.parseImport(SourceFile.virtual("<import>", stmt))
            else Nil
          } catch {
            case MillDriver.SyntaxError(msg) =>
              msgs += msg
              Nil
          }
        (stmt, imports)
    val errs = msgs.result()
    if errs.nonEmpty then
      sys.error(errs.mkString("\n"))
    // else
    //   sys.error(s"success, imports:\n>  ${res.map((c, i) => s"[$c] => [$i]").mkString("\n>  ")}")
    res
  }

  def parseObjectData(rawCode: String): Seq[ObjectData] = {
    // sys.error(s"Not implemented:\n$rawCode")
    val source = SourceFile.virtual("<script>", rawCode)
    try {
      MillDriver.parseObjects(source)
    } catch {
      case MillDriver.SyntaxError(msg) =>
        sys.error(msg)
    }
  }

  private case class Snippet(text: String = null, start: Int = -1, end: Int = -1) extends Snip

  private case class ObjectDataImpl(
      obj: Snippet,
      name: Snippet,
      parent: Snippet,
      endMarker: Option[Snippet],
      finalStat: Option[(String, Snippet)]
  ) extends ObjectData

  private object MillDriver extends Driver with MessageRendering {
    // override def newCompiler(using Context): MillCompiler = MillCompiler()

    // def parseSingle(source: SourceFile): Either[List[String], (Seq[String], Seq[String])] = {
    //   bootstrapClasspath match {
    //     case classpath =>
    //       parseSingle0(source, classpath)

    //     // This is a test for parallel compilation
    //     // import scala.concurrent.Future
    //     // import scala.concurrent.ExecutionContext.Implicits.global
    //     // import scala.concurrent.Await
    //     // import scala.concurrent.duration.Duration
    //     // val futs = Future.sequence(
    //     //   for _ <- 0 until 1_000 yield Future {
    //     //     parseSingle0(source, classpath)
    //     //   }
    //     // )
    //     // val res = Await.result(futs, Duration.Inf)
    //     // Left(List(
    //     //   s"${res.count(_.isRight)} out of ${res.size} successful:\n${res.find(_.isLeft).map(
    //     //       _.swap.getOrElse(Nil).mkString("\n")
    //     //     ).getOrElse("<no errors>")}"
    //     // ))
    //     case null =>
    //       Left(List(s"""Incorrect configuration for ${worker.getClass()}:
    //                    |No bootstrap classpath provided to the compiler.""".stripMargin))
    //   }
    // }

    def parseSingle0(
        source: SourceFile
        // classpath: String
    ): Either[List[String], (Seq[String], Seq[String])] = unitContext(source) {
      try {
        for
          tree <- failing(outlineParse(source))
          split <- failing(splitTrees(tree))
        yield split
      } catch {
        case ex: Exception =>
          Left(List(
            (s"Unhandled exception thrown during compilation: ${ex.getClass.getName}: ${ex.getMessage}\n${ex.getStackTrace.map(
                e => s"  $e"
              ).mkString("\n")}")
          ))
      }
    }

    def parseImport(source: SourceFile): Seq[ImportTree] = unitContext(source) {
      val trees = importParse(source)
      // syntax was already checked in splitScript, so any errors would suggest a bug
      assert(!ctx.reporter.hasErrors, "Import parsing should not have errors.")
      val msgs = List.newBuilder[String]
      val res = importTrees(trees).map({
        case s: String =>
          msgs += s
          ImportTree(Nil, Nil, 0, 0)
        case i: ImportTree => i
      })
      val errs = msgs.result()
      if errs.nonEmpty then
        throw SyntaxError(errs.mkString("\n"))
      res
    }

    def parseObjects(source: SourceFile): Seq[ObjectData] = unitContext(source) {
      val tree = outlineParse(source)
      // syntax was already checked in splitScript, so any errors would suggest a bug
      assert(!ctx.reporter.hasErrors, "valid script parsing should not have errors.")
      val msgs = List.newBuilder[String]
      // sys.error(s"Not implemented:\n=====\n${source.content().mkString}\n=====\n${tree.show}\n=====\n$tree")
      val res = objectDatas(tree).map({
        case s: String =>
          msgs += s
          ObjectDataImpl(Snippet(), Snippet(), Snippet(), None, None)
        case o: ObjectDataImpl => o
      })
      val errs = msgs.result()
      if errs.nonEmpty then
        throw SyntaxError(errs.mkString("\n"))
      res
    }

    private inline def failing[T](op: Context ?=> T)(using Context): Either[List[String], T] =
      val res = op
      if ctx.reporter.hasErrors then
        Left(removeBufferedErrors())
      else
        Right(res)

    private def removeBufferedErrors()(using Context): List[String] = {
      val errs = ctx.reporter.removeBufferedMessages.collect {
        case err: Diagnostic.Error => err
      }
      errs.map(d =>
        messageAndPos(d).replaceAll("\u001B\\[[;\\d]*m", "")
        // dottyStyleMessage(color = false, d).replaceAll("\u001B\\[[;\\d]*m", "")
      )
    }

    // TODO: remove
    final case class SyntaxError(msg: String) extends Exception(msg)

    /** Dotty parsers need to be synchronized */
    private object ParseLock extends java.util.concurrent.locks.ReentrantLock {
      inline def sync[T](inline op: T): T = {
        try {
          lock()
          op
        } finally {
          unlock()
        }
      }
    }

    def outlineParse(source: SourceFile)(using Context): untpd.Tree = ParseLock.sync {
      OutlineParser(source).parse()
    }

    def importParse(source: SourceFile)(using Context): List[untpd.Tree] = ParseLock.sync {
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

    def skipModsObjectOffset(offset: Int)(using Context): Int = ParseLock.sync {
      val in0 = Scanner(ctx.source, startFrom = offset)
      val parser = new OutlineParser(ctx.source) {
        override val in = in0
      }
      val mods = parser.defAnnotsMods(Tokens.modifierTokens)
      assert(in0.token == Tokens.OBJECT, s"Expected `object`, got ${in0.token}")
      in0.offset
    }

    def scanTemplateBody(offset: Int)(using Context): untpd.Template = ParseLock.sync {
      val in0 = Scanner(ctx.source, startFrom = offset)

      // parser that will enter a template body, but then not parse nested templates
      val parser = new Parsers.Parser(ctx.source) {
        var inTemplate = false

        val outlineParser: OutlineParser = new OutlineParser(ctx.source) {
          override val in = in0
        }

        override val in = in0

        override def blockExpr(): untpd.Tree = outlineParser.blockExpr()

        override def templateBody(parents: List[untpd.Tree], rewriteWithColon: Boolean) =
          if inTemplate then outlineParser.templateBody(parents, rewriteWithColon)
          else {
            try {
              inTemplate = true
              super.templateBody(parents, rewriteWithColon)
            } finally {
              inTemplate = false
            }
          }
      }
      parser.templateOpt(untpd.emptyConstructor)
    }

    /** While just parsing it isn't necessary to refresh the context */
    override protected val initCtx: Context = super.initCtx

    private final class MillStoredReporter extends StoreReporter(null) with UniqueMessagePositions
        with HideNonSensicalMessages {
      def dropAll(): Unit = infos = null
    }

    private inline def unitContext[T](source: SourceFile)(inline op: Context ?=> T): T = {
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

  private def syntaxError(msg0: String)(using Context): Message =
    new Message(ErrorMessageID.NoExplanationID) {
      override def kind: MessageKind = MessageKind.Syntax
      override protected def msg(using Context): String = msg0
      override protected def explain(using Context): String = ""
    }

  private def objectDatas(tree: untpd.Tree)(using Context): Seq[String | ObjectDataImpl] = {
    val buf = Seq.newBuilder[String | ObjectDataImpl]

    def moduleDef(mdef: untpd.ModuleDef): Option[ObjectDataImpl] = {
      val untpd.ModuleDef(name, impl) = mdef
      impl.parents match
        case parent :: _ =>
          val (name0, expanded) = {
            val content = ctx.source.content()
            val start0 = mdef.sourcePos.point
            val nameStr = name.show
            val end0 = start0 + nameStr.length
            if content.isDefinedAt(start0 - 1) && content(start0 - 1) == '`' && content.isDefinedAt(
                end0 + 1
              )
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
          val objOffset = MillDriver.skipModsObjectOffset(mdef.sourcePos.start)
          val obj0 = {
            val start0 = objOffset
            val end0 = start0 + "object".length()
            val text = slice(start0, end0)
            assert(text == "object", s"expected `object`, actually was `$text`")
            Snippet(text, start0, end0)
          }
          val parent0 = {
            val start0 = parent.sourcePos.start
            val end0 = parent.sourcePos.end
            val text = slice(start0, end0)
            Snippet(text, start0, end0)
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
            val impl1 = MillDriver.scanTemplateBody(name0.end)
            // buf += s"read inner object\n${impl1.body.map(b => s">  $b").mkString("\n")}"
            val leading0 = impl1.body.headOption.map({ stat =>
              val start0 = ctx.source.startOfLine(stat.sourcePos.start)
              val end0 = stat.sourcePos.start
              val leading = slice(start0, end0)
              leading
            })
            val stat0 = impl1.body.lastOption.map(stat => {
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
          // buf += s"computed object from [<{${slice(mdef.sourcePos.start, objOffset)}}><{${slice(objOffset, mdef.sourcePos.point)}}>,<{${slice(mdef.sourcePos.point, mdef.sourcePos.end)}}>] >>> $obj"
          // None
          Some(obj)
        case parents0 =>
          buf += s"IGNORED parents $parents0"
          None
    }

    def topLevel(trees: List[untpd.Tree]): Unit = trees match {
      case (mdef @ untpd.ModuleDef(_, _)) :: trees1 if mdef.sourcePos.span.exists =>
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

    compilationUnit(tree :: Nil)

    buf.result()
  }

  private def importTrees(trees: List[untpd.Tree])(using Context): Seq[String | ImportTree] = {
    val buf = Seq.newBuilder[String | ImportTree]

    def prefixAsSeq(pre: untpd.Tree, acc: List[(String, Int)]): Option[(Int, Seq[(String, Int)])] =
      pre match {
        case untpd.Ident(name) =>
          // the innermost part of an import prefix is always an `Ident`
          name.show match
            case millName @ s"$$$_" =>
              Some(pre.sourcePos.start -> ((millName -> pre.sourcePos.end) :: acc))
            case _ => None // first index wasn't a $foo import, so ignore
        case untpd.Select(qual, name) =>
          // Select(qual, name) ==> qual.name
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
            ("_", None) // TODO: verify behavior (e.g. `import foo.{given T}`)
        }
        ImportTree(prefix1, sels1, start, tree.sourcePos.end)
      }
    }

    def loop(trees: List[untpd.Tree]): Unit = trees match {
      case (head @ untpd.Import(prefix, sels)) :: trees1 if head.sourcePos.span.exists =>
        for tree <- importTree(head) do {
          buf += tree
        }
        loop(trees1)
      case tree :: trees1 =>
        buf += s"IGNORED $tree"
        loop(trees1)
      case Nil => ()
    }

    loop(trees)

    buf.result()
  }

  private def slice(start: Int, end: Int)(using Context): String =
    ctx.source.content.slice(start, end).mkString

  private def splitTrees(tree: untpd.Tree)(using Context): (Seq[String], Seq[String]) = {
    val topLevelPkgs = Seq.newBuilder[String]
    val topLevelStats = Seq.newBuilder[String]
    val inEmptyPackage = tree match {
      case untpd.PackageDef(untpd.Ident(nme.EMPTY_PACKAGE), _) => true
      case _ => false
    }

    def syntheticImport(tree: untpd.Import): Boolean =
      val span = tree.sourcePos.span
      span.exists && MillDriver.nextTokenIsntImport(span.start)

    /** We need to pack consecutive imports where the following is synthetic */
    def importRest(start: Int, from: Int, trees: List[untpd.Tree]): Unit = trees match {
      case (tree @ untpd.Import(_, _)) :: trees1 if syntheticImport(tree) =>
        // import comes from a comma separated list of imports
        importRest(start, tree.sourcePos.end, trees1)
      case _ =>
        // next statement wasnt a synthetic import, collect the imports
        topLevelStats += slice(start, from)
        topLevel(from, trees)
    }

    def topLevel(from: Int, trees: List[untpd.Tree]): Unit = trees match {
      case tree :: trees1 =>
        val span = tree.sourcePos.span
        if span.exists then
          topLevelStats += slice(from, span.start)
          tree match {
            case untpd.Import(_, _) =>
              importRest(span.start, span.end, trees1)
            case _ =>
              topLevelStats += slice(span.start, span.end)
              topLevel(span.end, trees1)
          }
        else
          report.error(syntaxError(s"Synthetic span on tree ${tree.show}."), tree.sourcePos)
          topLevel(from, trees1)
      case Nil => ()
    }

    def compilationUnit(from: Int, trees: List[untpd.Tree]): Unit = {
      trees match {
        case untpd.PackageDef(pid, stats) :: Nil =>
          val span = pid.sourcePos.span
          if span.exists && MillDriver.nextTokenIsntBlock(span.end) then
            if span.isSynthetic then
              assert(inEmptyPackage, "Synthetic package should only be in the empty package.")
              // dotty will always insert a package `<empty>` if there is no package declaration,
              // or if there is an import before the package declaration.
              // TODO: add regression test for the error for no package declaration
              compilationUnit(span.end, stats)

              // val nextIsPackage = stats.match {
              //   case untpd.PackageDef(_, _) :: _ => true
              //   case _ => false
              // }

              // val msg = if nextIsPackage then
              //   // this typically happens when two packages are declared in the same file
              //   "Mill build files can not have more than one package."
              // else
              //   "Mill requires a package declaration at the top of a build file."

              // report.error(syntaxError(msg), pid.sourcePos)
            else {
              if inEmptyPackage then
                report.error(
                  syntaxError("A package declaration must be first in a Mill build file"),
                  pid.sourcePos
                )
              else
                topLevelPkgs += pid.show
              compilationUnit(span.end, stats)
            }
          else
            report.error(syntaxError(s"Mill forbids packages to introduce a block."), pid.sourcePos)
        case _ =>
          topLevel(from, trees)
      }
    }

    compilationUnit(0, tree :: Nil)

    (topLevelPkgs.result(), topLevelStats.result())
  }

  // private class MillCompiler extends Compiler {
  //   override protected def frontendPhases: List[List[Phase]] = List(
  //     List(new MillParser)
  //   )

  //   override protected def picklerPhases: List[List[Phase]] = Nil

  //   override protected def transformPhases: List[List[Phase]] = Nil

  //   override protected def backendPhases: List[List[Phase]] = Nil

  //   // override def newRun(): MillRun = new MillRun
  // }

  // private class MillParser extends Parser {

  //   override def runOn(units: List[CompilationUnit])(using Context): List[CompilationUnit] = {
  //     val units1 = super.runOn(units)
  //     for
  //       treeVisitor <- ctx.property(TreeKey)
  //       unit <- units1
  //     do
  //       val src = unit.source
  //       treeVisitor.trees += (src.path -> unit.untpdTree.show)
  //     units1
  //   }

  // }

  /** Render the message in the style of dotty */
  // private def dottyStyleMessage(
  //     color: Boolean,
  //     problem0: Diagnostic
  // ): String = {
  //   val message = problem0.msg
  //   val base = message.message
  //   val severity = problem0.level

  //   val diagMessage = severity match
  //     case interfaces.Diagnostic.ERROR => "Error"
  //     case interfaces.Diagnostic.INFO => "Info"
  //     case interfaces.Diagnostic.WARNING => "Warning"
  //     case _ => ""

  //   val kind = {
  //     if message.kind == MessageKind.NoKind then diagMessage
  //     else {
  //       val kind = message.kind.message
  //       if diagMessage.isEmpty then kind
  //       else s"$kind $diagMessage"
  //     }
  //   }

  //   def shade(msg: String) = {
  //     // TODO: read color setting
  //     // if color then
  //     //   severity match {
  //     //     case xsbti.Severity.Error => Console.RED + msg + Console.RESET
  //     //     case xsbti.Severity.Warn => Console.YELLOW + msg + Console.RESET
  //     //     case xsbti.Severity.Info => Console.BLUE + msg + Console.RESET
  //     //   }
  //     // else
  //     msg
  //   }

  //   val normCode = {
  //     Option(message.errorId.errorNumber).filter(_ >= 0).map({ code =>
  //       val prefix = s"[E${"0" * (3 - code.toString.length)}$code] "
  //       if kind.isEmpty then prefix
  //       else
  //         s"$prefix$kind "
  //     }).getOrElse(if kind.isEmpty then "" else s"$kind ")
  //   }

  //   val emptySrc = problem0.pos.source.content.length == 0

  //   val pointer0 = {
  //     if emptySrc then -1
  //     else {
  //       val point =
  //         if problem0.pos.point >= 0 then problem0.pos.point
  //         else -1
  //       if point >= 0 then point - problem0.pos.source.startOfLine(point)
  //       else -1
  //     }
  //   }

  //   val line0 = if problem0.pos.line >= 0 then problem0.pos.line else -1

  //   val optPath = Option(problem0.pos.source.path).filter(_.nonEmpty).map { path =>
  //     if line0 >= 0 && pointer0 >= 0 then
  //       s"$path:${line0 + 1}:${pointer0 + 1}"
  //     else
  //       path
  //   }

  //   val normHeader = optPath.map(path =>
  //     s"${shade(s"-- $normCode$path")}\n"
  //   ).getOrElse("")

  //   val snippet = {
  //     if emptySrc then
  //       ""
  //     else
  //       problem0.pos.lineContent.stripLineEnd
  //   }

  //   val pointerSpace = {
  //     if pointer0 < 0 then ""
  //     else {
  //       // Don't crash if pointer is out-of-bounds (happens with some macros)
  //       val fixedPointer = Math.min(pointer0, snippet.length);
  //       val result = new StringBuilder()
  //       for i <- 0 until fixedPointer do
  //         result.append(if snippet.charAt(i) == '\t' then '\t' else ' ');
  //       result.toString
  //     }
  //   }

  //   val optSnippet = {
  //     val endCol = if problem0.pos.span.exists && problem0.pos.endColumn >= 0 then
  //       problem0.pos.endColumn
  //     else pointer0 + 1
  //     if snippet.nonEmpty && pointer0 >= 0 && endCol >= 0 then
  //       val arrowCount =
  //         math.max(1, math.min(endCol - pointer0, snippet.length - pointerSpace.length))
  //       s"""$snippet
  //          |$pointerSpace${"^" * arrowCount}""".stripMargin
  //     else
  //       ""
  //   }

  //   val content = {
  //     if optSnippet.nonEmpty then {
  //       val initial = {
  //         s"""$optSnippet
  //            |$base
  //            |""".stripMargin
  //       }
  //       if line0 >= 0 then {
  //         // add margin with line number
  //         val lines = initial.linesWithSeparators.toVector
  //         val pre = { line0 + 1 }.toString
  //         val rest0 = " " * pre.length
  //         val rest = pre +: Vector.fill(lines.size - 1)(rest0)
  //         rest.lazyZip(lines).map((pre, line) => shade(s"$pre │") + line).mkString
  //       } else {
  //         initial
  //       }
  //     } else {
  //       base
  //     }
  //   }

  //   normHeader + content
  // }

}
