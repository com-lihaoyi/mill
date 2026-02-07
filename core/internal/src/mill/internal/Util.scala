package mill.internal

import scala.reflect.NameTransformer.encode
import mill.api.Result
import mill.api.Logger
import mill.api.ExecResult
import mill.api.Result.Failure.ExceptionInfo
import mill.api.internal.HeaderData
import mill.api.daemon.internal.ExecutionResultsApi
import scala.collection.mutable

object Util {

  private[mill] def catchUpickleAbort[T](
      path: java.nio.file.Path,
      prefix: String = ""
  )(t: => T): Result[T] = {
    try Result.Success(t)
    catch {
      case abort: upickle.core.AbortException =>
        Result.Failure(
          prefix + Option(abort.getMessage).getOrElse("YAML type mismatch"),
          path,
          abort.index
        )
    }
  }

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
    case _ =>
      if (encode(s) == s && !alphaKeywords.contains(s) && Character.isJavaIdentifierStart(s.head)) s
      else "`" + s + "`"
  }

  def getLineNumber(text: String, index: Int): String = {
    fastparse.IndexedParserInput(text).prettyIndex(index).takeWhile(_ != ':')
  }

  def formatError(f: Result.Failure, highlight: String => String) = {
    Result.Failure
      .split(f)
      .map(f0 =>
        formatError0(f0.path, f0.index, f0.error, f0.exception, f0.tickerPrefix, highlight)
      )
      .mkString("\n")
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
  def formatError0(
      path: java.nio.file.Path,
      index: Int,
      message: String,
      exception: Seq[ExceptionInfo],
      tickerPrefix: String,
      highlight: String => String
  ): String = {
    val exceptionSuffix =
      if (exception.nonEmpty) Some(formatException(exception, highlight)) else None

    val positionedMessage =
      if (path == null || !java.nio.file.Files.exists(path))
        "[" + highlight("error") + "] " + message
      else {
        val text = java.nio.file.Files.readString(path)
        val indexedParser = fastparse.IndexedParserInput(text.replace("//| ", "").replace("\r", ""))
        val prettyIndex = indexedParser.prettyIndex(index)
        val Array(lineNum, colNum0) = prettyIndex.split(':').map(_.toInt)

        // Get the line content
        val lines = text.linesIterator.toVector
        val lineContent = if (lineNum > 0 && lineNum <= lines.length) lines(lineNum - 1) else ""

        // Offset column by 4 if line starts with "//| " to account for stripped YAML prefix (including space)
        val colNum = if (lineContent.startsWith("//| ")) colNum0 + 4 else colNum0

        mill.constants.Util.formatError(
          mill.api.BuildCtx.workspaceRoot.toNIO.relativize(path).toString,
          lineNum,
          colNum,
          lineContent,
          message,
          s => highlight(s)
        )
      }

    val prefix = highlight(tickerPrefix)

    (Seq(prefix + positionedMessage) ++ exceptionSuffix).mkString("\n")
  }

  def formatException(exception: Seq[ExceptionInfo], highlight: String => String): String = {
    val output = mutable.Buffer.empty[fansi.Str]
    for (ExceptionInfo(clsName, msg, stack) <- exception) {
      val exCls = highlight(clsName)
      output.append(
        msg match {
          case null => fansi.Str(exCls)
          case nonNull => fansi.Str.join(Seq(exCls, ": ", nonNull))
        }
      )

      for (frame <- stack) {
        val filenameFrag: fansi.Str = frame.getFileName match {
          case null => "Unknown"
          case fileName =>
            fansi.Str(highlight(fileName), ":", highlight(frame.getLineNumber.toString))
        }

        output.append(
          fansi.Str(
            "  ",
            highlight(frame.getClassName + "." + frame.getMethodName),
            "(",
            filenameFrag,
            ")"
          )
        )
      }
    }
    fansi.Str.join(output, "\n").render
  }

  def parseHeaderData(scriptFile: os.Path): Result[HeaderData] = {
    val headerDataOpt = mill.api.BuildCtx.withFilesystemCheckerDisabled {
      // If the module file got deleted, handle that gracefully
      if (!os.exists(scriptFile)) Result.Success("")
      else mill.api.ExecResult.catchWrapException {
        mill.constants.Util.readBuildHeader(scriptFile.toNIO, scriptFile.last, true)
          .replace("\r", "")
      }
    }

    def relativePath = scriptFile.relativeTo(mill.api.BuildCtx.workspaceRoot)
    given upickle.Reader[HeaderData] = HeaderData.headerDataReader(scriptFile)
    headerDataOpt.flatMap(parseYaml0(
      relativePath.toString,
      _,
      upickle.reader[HeaderData]
    ))
  }

  def parseYaml0[T](
      fileName: String,
      headerData: String,
      visitor0: upickle.core.Visitor[_, T]
  ): Result[T] = {

    val filePath = os.Path(fileName, mill.api.BuildCtx.workspaceRoot).toNIO
    try catchUpickleAbort(filePath) {
        upickle.core.TraceVisitor.withTrace(true, visitor0) { visitor =>
          import org.snakeyaml.engine.v2.api.LoadSettings
          import org.snakeyaml.engine.v2.composer.Composer
          import org.snakeyaml.engine.v2.parser.ParserImpl
          import org.snakeyaml.engine.v2.scanner.StreamReader
          import org.snakeyaml.engine.v2.nodes.*
          import scala.jdk.CollectionConverters.*

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
                  // Parse all YAML scalars as strings. In general, the `upickle.Reader`s that
                  // consume these events downstream all are able to handle strings elegantly,
                  // and this avoids the loss of precision that may result if we try to emit
                  // e.g. booleans using `visitTrue`/`visitFalse which would result in the
                  // distinction between `true`/`True`/`TRUE` collapsing into just `true` even
                  // if the final parse wants the actual string value.
                  scalar.getTag.getValue match {
                    case "tag:yaml.org,2002:null" => v.visitNull(index)
                    case _ => v.visitString(scalar.getValue, index)
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
                  def visitSequence[T](visitor: upickle.core.Visitor[?, T]): T = {
                    val arrVisitor = visitor.visitArray(sequence.getValue.size(), index)
                      .asInstanceOf[upickle.core.ArrVisitor[Any, T]]
                    for (item <- sequence.getValue.asScala) {
                      arrVisitor.visitValue(
                        rec(item, arrVisitor.subVisitor),
                        item.getStartMark.map(_.getIndex.intValue()).orElse(0)
                      )
                    }
                    arrVisitor.visitEnd(index)
                  }
                  // Check for !append tag - if present, wrap in {$millAppend: <array>}
                  if (sequence.getTag.getValue == "!append") {
                    import mill.api.internal.Appendable.AppendMarkerKey
                    val objVisitor = v.visitObject(1, jsonableKeys = true, index)
                      .asInstanceOf[upickle.core.ObjVisitor[Any, J]]
                    objVisitor.visitKeyValue(objVisitor.visitKey(index).visitString(
                      AppendMarkerKey,
                      index
                    ))
                    objVisitor.visitValue(visitSequence(objVisitor.subVisitor), index)
                    objVisitor.visitEnd(index)
                  } else {
                    visitSequence(v)
                  }
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
        e.getCause match {
          case e: org.snakeyaml.engine.v2.exceptions.ParserException =>
            val mark = e.getProblemMark.or(() => e.getContextMark)
            if (mark.isPresent) {
              val m = mark.get()
              val problem = Option(e.getProblem).getOrElse("YAML syntax error")
              Result.Failure(
                problem,
                os.Path(fileName, mill.api.BuildCtx.workspaceRoot).toNIO,
                m.getIndex
              )
            } else {
              Result.Failure(
                s"Failed parsing build header in $fileName: " + e.getMessage
              )
            }
          case abort: upickle.core.AbortException =>
            Result.Failure(
              s"Failed de-serializing config key ${e.jsonPath}: ${e.getCause.getCause.getMessage}",
              filePath,
              abort.index
            )

          case _ =>
            Result.Failure(
              s"$fileName Failed de-serializing config key ${e.jsonPath} ${e.getCause.getMessage}"
            )
        }
    }
  }

  /**
   * Parses a config value from the YAML header data.
   * Returns the parsed value or a default on missing key. Throws on parse failure.
   */
  def parseBuildHeaderValue[T: upickle.default.Reader](
      headerData: String,
      configKey: String,
      default: T
  ): T =
    parseYaml0(
      "build header",
      headerData,
      upickle.default.reader[Map[String, ujson.Value]]
    ) match {
      case Result.Success(conf) =>
        conf.get(configKey) match {
          case Some(value) => upickle.default.read[T](value)
          case None => default
        }
      case f: Result.Failure =>
        throw new mill.api.daemon.MillException(s"Failed parsing build header: ${f.error}")
    }

  /**
   * Reads a boolean flag from the root build.mill YAML header.
   */
  def readBooleanFromBuildHeader(
      projectRoot: os.Path,
      configKey: String,
      rootBuildFileNames: Seq[String]
  ): Boolean = {
    rootBuildFileNames
      .map(name => projectRoot / name)
      .find(os.exists)
      .exists { buildFile =>
        val headerData = mill.constants.Util.readBuildHeader(buildFile.toNIO, buildFile.last)
        parseBuildHeaderValue[Boolean](headerData, configKey, default = false)
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

  def formatFailing(evaluated: ExecutionResultsApi): Result.Failure = {
    Result.Failure.join(
      for ((key, fs) <- evaluated.transitiveFailingApi.toSeq)
        yield {
          val keyPrefix =
            Logger.formatPrefix(evaluated.transitivePrefixesApi.getOrElse(key, Nil))

          def convertFailure(f: ExecResult.Failure[_]): Result.Failure = {
            f.failure match {
              // If there is no associated `Result.Failure`,
              // synthesize one based on the `key` and the `f.msg`
              case None => Result.Failure(error = s"$key ${f.msg}", tickerPrefix = keyPrefix)
              case Some(failure) =>
                // If there is an associated `Result.Failure` with no prefix, set the prefix to the
                // current `keyPrefix` and prefix `error` with `key`
                if (failure.tickerPrefix == "")
                  failure.copy(error = s"$key ${failure.error}", tickerPrefix = keyPrefix)
                // If there is an associated `Result.Failure` with its own prefix, preserve it
                // and chain together a new `Result.Failure` entry representing the current key
                else Result.Failure(error = s"$key", tickerPrefix = keyPrefix, next = Some(failure))
            }
          }

          fs match {
            case f: ExecResult.Failure[_] => convertFailure(f)
            case ex: ExecResult.Exception =>
              Result.Failure.fromException(ex.throwable, ex.outerStack.value.length).copy(
                error = key.toString,
                tickerPrefix = keyPrefix
              )
          }
        }
    )
  }

  /**
   * Creates a map of standard environment variables for interpolation in Mill config files.
   * This includes PWD, PWD_URI, WORKSPACE, MILL_VERSION, and MILL_BIN_PLATFORM.
   */
  def envForInterpolation(workDir: os.Path): Map[String, String] = {
    val workspaceDir = workDir.toString
    sys.env ++ Map(
      "PWD" -> workspaceDir,
      "PWD_URI" -> workDir.toNIO.toUri.toString,
      "WORKSPACE" -> workspaceDir,
      "MILL_VERSION" -> mill.constants.BuildInfo.millVersion,
      "MILL_BIN_PLATFORM" -> mill.constants.BuildInfo.millBinPlatform
    )
  }
}
