package mill.internal

import scala.reflect.NameTransformer.encode
import mill.api.Result
import mill.api.ModuleCtx.HeaderData

object Util {

  val alphaKeywords: Set[String] = Set(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "enum",
    "export",
    "extends",
    "false",
    "final",
    "finally",
    "forSome",
    "for",
    "given",
    "if",
    "implicit",
    "import",
    "lazy",
    "match",
    "new",
    "null",
    "object",
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "then",
    "this",
    "throw",
    "trait",
    "try",
    "true",
    "type",
    "val",
    "var",
    "while",
    "with",
    "yield",
    "_",
    "macro"
  )

  def backtickWrap(s: String): String = s match {
    case s"`$_`" => s
    case _ => if (encode(s) == s && !alphaKeywords.contains(s)) s
      else "`" + s + "`"
  }

  def getLineNumber(text: String, index: Int): String = {
    fastparse.IndexedParserInput(text).prettyIndex(index).takeWhile(_ != ':')
  }

  /**
   * Format an error message in dotty style with file location, code snippet, and pointer.
   *
   * @param fileName The file name or path to display
   * @param text The full text content of the file
   * @param index The character index where the error occurred
   * @param message The error message to display
   * @return A formatted error string with location, code snippet, pointer, and message
   */
  def formatError(fileName: String, text: String, index: Int, message: String): String = {
    val indexedParser = fastparse.IndexedParserInput(text)
    val prettyIndex = indexedParser.prettyIndex(index)
    val Array(lineNum, colNum0) = prettyIndex.split(':').map(_.toInt)

    // Get the line content
    val lines = text.linesIterator.toVector
    val lineContent = if (lineNum > 0 && lineNum <= lines.length) lines(lineNum - 1) else ""

    // Offset column by 4 if line starts with "//| " to account for stripped YAML prefix (including space)
    val colNum = if (lineContent.startsWith("//| ")) colNum0 + 4 else colNum0

    mill.api.internal.Util.formatError(fileName, lineNum, colNum, lineContent, message)
  }

  def parseHeaderData(scriptFile: os.Path): Result[HeaderData] = {
    val headerDataOpt = mill.api.BuildCtx.withFilesystemCheckerDisabled {
      // If the module file got deleted, handle that gracefully
      if (!os.exists(scriptFile)) Result.Success("")
      else mill.api.ExecResult.catchWrapException {
        mill.constants.Util.readBuildHeader(scriptFile.toNIO, scriptFile.last, true)
      }
    }

    def relativePath = scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot)
    val originalText = if (os.exists(scriptFile)) os.read(scriptFile) else ""

    headerDataOpt.flatMap(parseYaml0(
      relativePath.toString,
      _,
      originalText,
      upickle.reader[HeaderData]
    ))
  }

  def parseYaml0[T](
      fileName: String,
      headerData: String,
      originalText: String,
      visitor0: upickle.core.Visitor[_, T]
  ): Result[T] = {

    try Result.Success {
        upickle.core.TraceVisitor.withTrace(true, visitor0) { visitor =>
          import org.snakeyaml.engine.v2.api.LoadSettings
          import org.snakeyaml.engine.v2.composer.Composer
          import org.snakeyaml.engine.v2.parser.ParserImpl
          import org.snakeyaml.engine.v2.scanner.StreamReader
          import org.snakeyaml.engine.v2.nodes._
          import scala.jdk.CollectionConverters._

          val settings = LoadSettings.builder().build()
          val reader = new StreamReader(settings, headerData)
          val parser = new ParserImpl(settings, reader)
          val composer = new Composer(settings, parser)

          // recursively convert Node using the visitor, preserving character offsets
          def rec[J](node: Node, v: upickle.core.Visitor[_, J]): J = {
            val index = node.getStartMark.map(_.getIndex.intValue()).orElse(0)

            try {
              node match {
                case scalar: ScalarNode =>
                  val value = scalar.getValue
                  val tag = scalar.getTag.getValue
                  tag match {
                    case "tag:yaml.org,2002:null" => v.visitNull(index)
                    case "tag:yaml.org,2002:bool" =>
                      if (value == "true") v.visitTrue(index)
                      else v.visitFalse(index)
                    case "tag:yaml.org,2002:int" =>
                      v.visitFloat64StringParts(value, -1, -1, index)
                    case "tag:yaml.org,2002:float" =>
                      v.visitFloat64StringParts(value, -1, -1, index)
                    case _ => v.visitString(value, index)
                  }

                case mapping: MappingNode =>
                  val objVisitor =
                    v.visitObject(mapping.getValue.size(), jsonableKeys = true, index)
                      .asInstanceOf[upickle.core.ObjVisitor[Any, J]]
                  for (tuple <- mapping.getValue.asScala) {
                    val keyNode = tuple.getKeyNode
                    val valueNode = tuple.getValueNode
                    val keyIndex = keyNode.getStartMark.map(_.getIndex.intValue()).orElse(0)
                    val key = keyNode match {
                      case s: ScalarNode => s.getValue
                      case _ => keyNode.toString
                    }
                    val keyVisitor = objVisitor.visitKey(keyIndex)
                    objVisitor.visitKeyValue(keyVisitor.visitString(key, keyIndex))
                    val valueResult = rec(valueNode, objVisitor.subVisitor)
                    objVisitor.visitValue(
                      valueResult,
                      valueNode.getStartMark.map(_.getIndex.intValue()).orElse(0)
                    )
                  }
                  objVisitor.visitEnd(index)

                case sequence: SequenceNode =>
                  val arrVisitor = v.visitArray(sequence.getValue.size(), index)
                    .asInstanceOf[upickle.core.ArrVisitor[Any, J]]
                  for (item <- sequence.getValue.asScala) {
                    val itemResult = rec(item, arrVisitor.subVisitor)
                    arrVisitor.visitValue(
                      itemResult,
                      item.getStartMark.map(_.getIndex.intValue()).orElse(0)
                    )
                  }
                  arrVisitor.visitEnd(index)
              }
            } catch {
              case e: upickle.core.Abort =>
                throw upickle.core.AbortException(e.getMessage, index, -1, -1, e)
            }
          }

          // Treat a top-level `null` or empty document as an empty object
          if (composer.hasNext) {
            val node = composer.next()
            node match {
              case scalar: ScalarNode if scalar.getTag.getValue == "tag:yaml.org,2002:null" =>
                val index = node.getStartMark.map(_.getIndex.intValue()).orElse(0)
                val objVisitor = visitor.visitObject(0, jsonableKeys = true, index)
                objVisitor.visitEnd(index)
              case _ =>
                rec(node, visitor)
            }
          } else {
            val objVisitor = visitor.visitObject(0, jsonableKeys = true, 0)
            objVisitor.visitEnd(0)
          }
        }
      }
    catch {
      case e: upickle.core.TraceVisitor.TraceException =>
        val msg = e.getCause match {
          case e: org.snakeyaml.engine.v2.exceptions.ParserException =>
            s"Failed parsing build header in $fileName: " + e.getMessage
          case abort: upickle.core.AbortException =>
            formatError(
              fileName,
              originalText,
              abort.index,
              s"Failed de-serializing config key ${e.jsonPath}: ${e.getCause.getCause.getMessage}"
            )

          case _ =>
            s"$fileName Failed de-serializing config key ${e.jsonPath} ${e.getCause.getMessage}"
        }

        Result.Failure(msg)
    }
  }

  def splitPreserveEOL(bytes: Array[Byte]): Seq[Array[Byte]] = {
    val out = scala.collection.mutable.ArrayBuffer[Array[Byte]]()
    var i = 0
    val n = bytes.length
    import java.util.Arrays.copyOfRange
    while (i < n) {
      val start = i

      while (i < n && bytes(i) != '\n' && bytes(i) != '\r') i += 1 // Move to end-of-line

      if (i >= n) out += copyOfRange(bytes, start, n) // Last line with no newline
      else { // Found either '\n' or '\r'
        if (bytes(i) == '\r') { // CR
          if (i + 1 < n && bytes(i + 1) == '\n') { // CRLF
            i += 2
            out += copyOfRange(bytes, start, i)
          } else { // Lone CR
            i += 1
            out += copyOfRange(bytes, start, i)
          }
        } else { // LF
          i += 1
          out += copyOfRange(bytes, start, i)
        }
      }
    }

    out.toSeq
  }

}
