package mill.javalib.zinc

import com.github.javaparser.GeneratedJavaParserConstants.*
import com.github.javaparser.{GeneratedJavaParserTokenManager, Providers, SimpleCharStream, StaticJavaParser}
import com.github.javaparser.ast.visitor.VoidVisitorAdapter
import com.github.javaparser.ast.body.*
import com.github.javaparser.ast.expr.*
import com.github.javaparser.ast.`type`.*

object HighlightJava {

  def highlightJavaCode(
      sourceCode: String,
      literalColor: fansi.Attrs,
      keywordColor: fansi.Attrs,
      commentColor: fansi.Attrs,
      typeColor: fansi.Attrs,
      definitionColor: fansi.Attrs
  ): fansi.Str = {
    // Use the token manager directly for pure lexical tokenization
    // This works even on incomplete/invalid Java code snippets
    val charStream = new SimpleCharStream(Providers.provider(sourceCode))
    val tokenManager = new GeneratedJavaParserTokenManager(charStream)

    val overlays = collection.mutable.Buffer.empty[(fansi.Attrs, Int, Int)]

    // Collect semantic identifier info (definitions and types) from AST parsing
    val (definitionPositions, typePositions) = collectSemanticInfo(sourceCode)

    def addTokenOverlay(token: com.github.javaparser.Token): Unit = {
      // JavaParser token positions are 1-based, convert to 0-based offsets
      // For single-line input, we can use column positions directly
      val startOffset = token.beginColumn - 1
      val endOffset = token.endColumn // endColumn is inclusive, so no -1 needed for exclusive end

      // Clamp to valid indices
      val clampedStart = math.max(0, math.min(startOffset, sourceCode.length))
      val clampedEnd = math.max(0, math.min(endOffset, sourceCode.length))

      if (clampedStart < clampedEnd) {
        // Check if this is an identifier with semantic meaning
        val color: Option[fansi.Attrs] =
          if (token.kind == IDENTIFIER) {
            if (definitionPositions.contains((clampedStart, clampedEnd))) Some(definitionColor)
            else if (typePositions.contains((clampedStart, clampedEnd))) Some(typeColor)
            else None
          } else getColorOpt(token.kind, literalColor, keywordColor, commentColor)

        for (c <- color) overlays.append((c, clampedStart, clampedEnd))
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

  /**
   * Try to parse the source code and collect positions of definition names and type names.
   * This tries multiple parse strategies since we're dealing with code snippets, not full files.
   * @return a tuple of (definitionPositions, typePositions)
   */
  private def collectSemanticInfo(
      sourceCode: String
  ): (Set[(Int, Int)], Set[(Int, Int)]) = {
    val definitionPositions = collection.mutable.Set.empty[(Int, Int)]
    val typePositions = collection.mutable.Set.empty[(Int, Int)]

    // Visitor to collect definition names (method, class, variable, parameter names)
    // and type names from the AST
    class SemanticVisitor extends VoidVisitorAdapter[Void] {
      private def addDefinition(name: SimpleName): Unit = {
        for {
          tokenRange <- scala.jdk.OptionConverters.RichOptional(name.getTokenRange).toScala
          range <- scala.jdk.OptionConverters.RichOptional(tokenRange.getBegin.getRange).toScala
        } {
          // Convert to 0-based offsets
          val start = range.begin.column - 1
          val endOffset = range.end.column
          definitionPositions.add((start, endOffset))
        }
      }

      private def addType(name: SimpleName): Unit = {
        for {
          tokenRange <- scala.jdk.OptionConverters.RichOptional(name.getTokenRange).toScala
          range <- scala.jdk.OptionConverters.RichOptional(tokenRange.getBegin.getRange).toScala
        } {
          val start = range.begin.column - 1
          val endOffset = range.end.column
          typePositions.add((start, endOffset))
        }
      }

      // Class/interface/enum/record declarations
      override def visit(n: ClassOrInterfaceDeclaration, arg: Void): Unit = {
        addDefinition(n.getName)
        super.visit(n, arg)
      }

      override def visit(n: EnumDeclaration, arg: Void): Unit = {
        addDefinition(n.getName)
        super.visit(n, arg)
      }

      override def visit(n: RecordDeclaration, arg: Void): Unit = {
        addDefinition(n.getName)
        super.visit(n, arg)
      }

      override def visit(n: AnnotationDeclaration, arg: Void): Unit = {
        addDefinition(n.getName)
        super.visit(n, arg)
      }

      // Method and constructor declarations
      override def visit(n: MethodDeclaration, arg: Void): Unit = {
        addDefinition(n.getName)
        super.visit(n, arg)
      }

      override def visit(n: ConstructorDeclaration, arg: Void): Unit = {
        addDefinition(n.getName)
        super.visit(n, arg)
      }

      // Variable declarations (fields, local variables)
      override def visit(n: VariableDeclarator, arg: Void): Unit = {
        addDefinition(n.getName)
        super.visit(n, arg)
      }

      // Parameters
      override def visit(n: Parameter, arg: Void): Unit = {
        addDefinition(n.getName)
        super.visit(n, arg)
      }

      // Enum constants
      override def visit(n: EnumConstantDeclaration, arg: Void): Unit = {
        addDefinition(n.getName)
        super.visit(n, arg)
      }

      // Annotation member declarations
      override def visit(n: AnnotationMemberDeclaration, arg: Void): Unit = {
        addDefinition(n.getName)
        super.visit(n, arg)
      }

      // Type references - ClassOrInterfaceType represents type usage
      override def visit(n: ClassOrInterfaceType, arg: Void): Unit = {
        addType(n.getName)
        // Also visit scope for qualified types like java.util.List
        n.getScope.ifPresent(scope => visit(scope, arg))
        super.visit(n, arg)
      }
    }

    val visitor = new SemanticVisitor()

    // Try different parse strategies for code snippets
    // Order matters - try more specific constructs first
    def tryParse(parse: => com.github.javaparser.ast.Node): Boolean = {
      try {
        val ast = parse
        ast.accept(visitor, null)
        true
      } catch {
        case _: Exception => false
      }
    }

    // Try parsing as different constructs
    tryParse(StaticJavaParser.parse(sourceCode)) ||
    tryParse(StaticJavaParser.parseBlock(sourceCode)) ||
    tryParse(StaticJavaParser.parseStatement(sourceCode)) ||
    tryParse(StaticJavaParser.parseBodyDeclaration(sourceCode)) ||
    tryParse(StaticJavaParser.parseExpression(sourceCode))

    (definitionPositions.toSet, typePositions.toSet)
  }

  def getColorOpt(
      tokenKind: Int,
      literalColor: fansi.Attrs,
      keywordColor: fansi.Attrs,
      commentColor: fansi.Attrs
  ): Option[fansi.Attrs] = tokenKind match {
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
