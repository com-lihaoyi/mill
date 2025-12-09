package mill.javalib.zinc

import com.github.javaparser.GeneratedJavaParserConstants.*
import com.github.javaparser.{GeneratedJavaParserTokenManager, Providers, SimpleCharStream}

object HighlightJava {

  def highlightJavaCode(
      sourceCode: String,
      literalColor: fansi.Attrs,
      keywordColor: fansi.Attrs,
      commentColor: fansi.Attrs
  ): fansi.Str = {
    // Use the token manager directly for pure lexical tokenization
    // This works even on incomplete/invalid Java code snippets
    val charStream = new SimpleCharStream(Providers.provider(sourceCode))
    val tokenManager = new GeneratedJavaParserTokenManager(charStream)

    val overlays = collection.mutable.Buffer.empty[(fansi.Attrs, Int, Int)]

    def addTokenOverlay(token: com.github.javaparser.Token): Unit = {
      for (color <- getColorOpt(token.kind, literalColor, keywordColor, commentColor)) {
        // JavaParser token positions are 1-based, convert to 0-based offsets
        // For single-line input, we can use column positions directly
        val startOffset = token.beginColumn - 1
        val endOffset = token.endColumn // endColumn is inclusive, so no -1 needed for exclusive end

        // Clamp to valid indices
        val clampedStart = math.max(0, math.min(startOffset, sourceCode.length))
        val clampedEnd = math.max(0, math.min(endOffset, sourceCode.length))
        if (clampedStart < clampedEnd) overlays.append((color, clampedStart, clampedEnd))
      }
    }

    // Process special tokens (comments, whitespace) attached to a token
    def processSpecialTokens(token: com.github.javaparser.Token): Unit = {
      var special = token.specialToken
      while (special != null) {
        addTokenOverlay(special)
        special = special.specialToken
      }
    }

    try {
      var token = tokenManager.getNextToken()
      while (token.kind != EOF) {
        // First process any special tokens (comments) attached to this token
        processSpecialTokens(token)
        // Then process the token itself
        addTokenOverlay(token)
        token = tokenManager.getNextToken()
      }
      // Don't forget special tokens attached to EOF
      processSpecialTokens(token)
    } catch {
      // If tokenization fails partway through (e.g., unclosed string),
      // just use whatever tokens we've collected so far
      case _: Exception => ()
    }

    fansi.Str(sourceCode).overlayAll(overlays.toSeq)
  }

  def getColorOpt(tokenKind: Int,
                  literalColor: fansi.Attrs,
                  keywordColor: fansi.Attrs,
                  commentColor: fansi.Attrs): Option[fansi.Attrs] = tokenKind match {
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
