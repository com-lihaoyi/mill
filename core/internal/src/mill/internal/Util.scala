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

  def parseHeaderData(scriptFile: os.Path): Result[HeaderData] = {
    val headerDataOpt = mill.api.BuildCtx.withFilesystemCheckerDisabled {
      // If the module file got deleted, handle that gracefully
      if (!os.exists(scriptFile)) Result.Success("")
      else mill.api.ExecResult.catchWrapException {
        mill.constants.Util.readBuildHeader(scriptFile.toNIO, scriptFile.last, true)
      }
    }

    def relativePath = scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot)

    headerDataOpt.flatMap(parseYaml0(relativePath.toString, _, upickle.reader[HeaderData]))
  }

  def parseYaml0[T](
      fileName: String,
      headerData: String,
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
                val objVisitor = v.visitObject(mapping.getValue.size(), jsonableKeys = true, index)
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
      case e: org.snakeyaml.engine.v2.exceptions.ParserException =>
        Result.Failure(s"Failed de-serializing build header in $fileName: " + e.getMessage)
      case e: upickle.core.TraceVisitor.TraceException =>
        Result.Failure(
          s"Failed de-serializing config key ${e.jsonPath} in $fileName: ${e.getCause.getMessage}"
        )
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
