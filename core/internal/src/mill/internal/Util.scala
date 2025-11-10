package mill.internal

import scala.reflect.NameTransformer.encode
import mill.api.Result
import mill.api.ModuleCtx.HeaderData

private[mill] object Util {

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

  private[mill] def parseHeaderData(scriptFile: os.Path): Result[HeaderData] = {
    val headerData = mill.api.BuildCtx.withFilesystemCheckerDisabled {
      // If the module file got deleted, handle that gracefully
      if (!os.exists(scriptFile)) ""
      else mill.constants.Util.readBuildHeader(scriptFile.toNIO, scriptFile.last, true)
    }

    def relativePath = scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot)

    parseYaml(relativePath.toString, headerData).flatMap { parsed =>
      try Result.Success(upickle.read[HeaderData](parsed))
      catch {
        case e: upickle.core.TraceVisitor.TraceException =>
          Result.Failure(
            s"Failed de-serializing config key ${e.jsonPath} in $relativePath: ${e.getCause.getMessage}"
          )
      }
    }
  }

  def parseYaml(fileName: String, headerData: String): Result[ujson.Value] =
    parseYaml0(fileName, headerData).map(upickle.core.BufferedValue.transform(_, ujson.Value))

  def parseYaml0(fileName: String, headerData: String): Result[upickle.core.BufferedValue] =
    try Result.Success {
        import org.snakeyaml.engine.v2.api.{LoadSettings}
        import org.snakeyaml.engine.v2.composer.Composer
        import org.snakeyaml.engine.v2.parser.ParserImpl
        import org.snakeyaml.engine.v2.scanner.StreamReader
        import org.snakeyaml.engine.v2.nodes._
        import scala.jdk.CollectionConverters._
        import scala.collection.mutable.ArrayBuffer

        val settings = LoadSettings.builder().build()
        val reader = new StreamReader(settings, headerData)
        val parser = new ParserImpl(settings, reader)
        val composer = new Composer(settings, parser)

        // recursively convert Node to upickle.core.BufferedValue, preserving character offsets
        def rec(node: Node): upickle.core.BufferedValue = {
          val index = node.getStartMark.map(_.getIndex.intValue()).orElse(0)

          node match {
            case scalar: ScalarNode =>
              val value = scalar.getValue
              val tag = scalar.getTag.getValue
              tag match {
                case "tag:yaml.org,2002:null" => upickle.core.BufferedValue.Null(index)
                case "tag:yaml.org,2002:bool" =>
                  if (value == "true") upickle.core.BufferedValue.True(index)
                  else upickle.core.BufferedValue.False(index)
                case "tag:yaml.org,2002:int" =>
                  upickle.core.BufferedValue.Num(value, -1, -1, index)
                case "tag:yaml.org,2002:float" =>
                  upickle.core.BufferedValue.Num(value, -1, -1, index)
                case _ => upickle.core.BufferedValue.Str(value, index)
              }

            case mapping: MappingNode =>
              val pairs = mapping.getValue.asScala.map { tuple =>
                val keyNode = tuple.getKeyNode
                val valueNode = tuple.getValueNode
                val key = keyNode match {
                  case s: ScalarNode => upickle.core.BufferedValue.Str(s.getValue, keyNode.getStartMark.map(_.getIndex.intValue()).orElse(0))
                  case _ => upickle.core.BufferedValue.Str(keyNode.toString, keyNode.getStartMark.map(_.getIndex.intValue()).orElse(0))
                }
                (key, rec(valueNode))
              }
              upickle.core.BufferedValue.Obj(ArrayBuffer.from(pairs), jsonableKeys = true, index)

            case sequence: SequenceNode =>
              val items = sequence.getValue.asScala.map(rec)
              upickle.core.BufferedValue.Arr(ArrayBuffer.from(items), index)
          }
        }

        // Treat a top-level `null` or empty document as an empty object
        if (composer.hasNext) {
          val node = composer.next()
          rec(node) match {
            case nullValue @ upickle.core.BufferedValue.Null(_) =>
              upickle.core.BufferedValue.Obj(ArrayBuffer.empty, jsonableKeys = true, nullValue.index)
            case v => v
          }
        } else {
          upickle.core.BufferedValue.Obj(ArrayBuffer.empty, jsonableKeys = true, 0)
        }
      }
    catch {
      case e: org.snakeyaml.engine.v2.exceptions.ParserException =>
        Result.Failure(s"Failed de-serializing build header in $fileName: " + e.getMessage)
    }

  def validateBuildHeaderKeys(
      buildOverridesKeys: Set[String],
      allTaskNames: Set[String],
      relativeScriptFilePath: os.SubPath
  ) = {
    val invalidBuildOverrides = buildOverridesKeys
      .filter(!allTaskNames.contains(_))
      .filter(!_.contains('-'))

    if (invalidBuildOverrides.nonEmpty) {
      val pretty = invalidBuildOverrides.map(pprint.Util.literalize(_)).mkString(",")
      throw new Exception(
        s"invalid build config in `$relativeScriptFilePath`: key $pretty does not override any task"
      )
    }
  }
}
