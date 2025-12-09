package mill.javalib.zinc

import com.github.javaparser.GeneratedJavaParserConstants.*
import com.github.javaparser.{JavaParser, JavaToken, ParseStart, Providers}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.RichOptional

object HighlightJava {

  def highlightJavaCode(
      sourceCode: String,
      literalColor: fansi.Attrs,
      keywordColor: fansi.Attrs,
      commentColor: fansi.Attrs
  ): fansi.Str = {
    val parser = new JavaParser()

    // Try to parse as a compilation unit first to get tokens
    val parsed = parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(sourceCode))

    // Calculate line lengths for converting positions to offsets
    val lines = sourceCode.linesIterator.toArray
    val lineLengths = lines.map(_.length)

    def positionToOffset(p: com.github.javaparser.Position): Int = {
      // Sum up lengths of all previous lines plus newline characters, then add column offset
      lineLengths.iterator.take(p.line - 1).sum + (p.line - 1) + (p.column - 1)
    }

    val output0 = fansi.Str(sourceCode)
    val overlays = for{
      result <- parsed.getResult.asScala.toSeq
      tokenRange <- result.getTokenRange.asScala.toSeq
      token <- tokenRange.asScala
      color <- getColorOpt(token, literalColor, keywordColor, commentColor).toSeq
      range <- token.getRange.asScala

      // Clamp to valid indices
      clampedStart = math.max(0, math.min(positionToOffset(range.begin), sourceCode.length))
      // JavaParser range end is inclusive, so we add 1 for the exclusive end
      clampedEnd = math.max(0, math.min(positionToOffset(range.end) + 1, sourceCode.length))
      if clampedStart < clampedEnd
    } yield (color, clampedStart, clampedEnd)

    fansi.Str(sourceCode).overlayAll(overlays.toSeq)
  }

  def getColorOpt(token: JavaToken,
                  literalColor: fansi.Attrs,
                  keywordColor: fansi.Attrs,
                  commentColor: fansi.Attrs): Option[fansi.Attrs] = token.getKind match {
    // Literals
    case INTEGER_LITERAL | LONG_LITERAL | FLOATING_POINT_LITERAL |
         CHARACTER_LITERAL | STRING_LITERAL | TEXT_BLOCK_LITERAL |
         TRUE | FALSE | NULL =>
      Some(literalColor)

    // Keywords - use backticks for _DEFAULT since underscore has special meaning in Scala 3
    case ABSTRACT | ASSERT | BOOLEAN | BREAK | BYTE | CASE |
         CATCH | CHAR | CLASS | CONST | CONTINUE | `_DEFAULT` |
         DO | DOUBLE | ELSE | ENUM | EXTENDS | FINAL | FINALLY |
         FLOAT | FOR | GOTO | IF | IMPLEMENTS | IMPORT | INSTANCEOF |
         INT | INTERFACE | LONG | NATIVE | NEW | PACKAGE | PRIVATE |
         PROTECTED | PUBLIC | RETURN | SHORT | STATIC | STRICTFP |
         SUPER | SWITCH | SYNCHRONIZED | THIS | THROW | THROWS |
         TRANSIENT | TRY | VOID | VOLATILE | WHILE |
         // Java 9+ module keywords
         MODULE | OPEN | REQUIRES | EXPORTS | OPENS | USES | PROVIDES |
         TO | WITH | TRANSITIVE |
         // Java 14+ keywords
         RECORD | YIELD |
         // Java 17+ keywords
         SEALED | NON_SEALED | PERMITS |
         // Java 21+ keywords
         WHEN =>
      Some(keywordColor)

    // Comments
    case SINGLE_LINE_COMMENT | MULTI_LINE_COMMENT | JAVADOC_COMMENT =>
      Some(commentColor)

    case _ => None
  }

}
