package mill.api.internal

private[mill] case class HeaderData(
    `extends`: Located[OneOrMore[Located[String]]] = Located(null, -1, OneOrMore(Nil)),
    moduleDeps: Located[Appendable[Seq[Located[String]]]] =
      Located(null, -1, Appendable(Nil)),
    compileModuleDeps: Located[Appendable[Seq[Located[String]]]] =
      Located(null, -1, Appendable(Nil)),
    runModuleDeps: Located[Appendable[Seq[Located[String]]]] =
      Located(null, -1, Appendable(Nil)),
    bomModuleDeps: Located[Appendable[Seq[Located[String]]]] =
      Located(null, -1, Appendable(Nil)),
    `mill-experimental-precompiled-module`: Located[Boolean] = Located(null, -1, false),
    @upickle.implicits.flatten rest: Map[Located[String], upickle.core.BufferedValue]
)
private[mill] object HeaderData {

  import upickle.core.BufferedValue

  private def nestedHeaderDataError(
      scriptPath: os.Path,
      abort: upickle.core.AbortException
  ) = {
    val message = Option(abort.getMessage).getOrElse("YAML type mismatch")
    throw new mill.api.daemon.Result.Exception(
      message,
      Some(mill.api.daemon.Result.Failure(message, scriptPath.toNIO, abort.index))
    )
  }

  private implicit val bufferedR: upickle.Reader[BufferedValue] =
    new upickle.Reader.Delegate(BufferedValue.Builder)

  def headerDataReader(path: os.Path) = {
    implicit def locatedReader[T: upickle.Reader]: Located.UpickleReader[T] =
      new Located.UpickleReader[T](path)
    implicit def appendableReader[T: upickle.Reader]: Appendable.UpickleReader[T] =
      new Appendable.UpickleReader[T]
    upickle.macroR[HeaderData]
  }

  /**
   * Parses header data from a YAML config file.
   */
  private[mill] def parseHeaderData(scriptFile: os.Path): mill.api.Result[HeaderData] = {
    val headerDataOpt = mill.api.BuildCtx.withFilesystemCheckerDisabled {
      if (!os.exists(scriptFile)) mill.api.Result.Success("")
      else mill.api.ExecResult.catchWrapException {
        mill.constants.Util.readBuildHeader(scriptFile.toNIO, scriptFile.last, true)
          .replace("\r", "")
      }
    }
    given upickle.Reader[HeaderData] = headerDataReader(scriptFile)
    headerDataOpt.flatMap(parseYaml0(scriptFile, _, upickle.reader[HeaderData]))
  }

  private[mill] def parseYaml0[T](
      scriptFile: os.Path,
      headerData: String,
      visitor0: upickle.core.Visitor[_, T]
  ): mill.api.Result[T] = {
    try mill.api.Result.Success {
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

          def rec[J](node: Node, v: upickle.core.Visitor[_, J]): J = {
            val index = node.getStartMark.map(_.getIndex.intValue()).orElse(0)
            try {
              node match {
                case scalar: ScalarNode =>
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
                  if (sequence.getTag.getValue == "!append") {
                    import Appendable.AppendMarkerKey
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
              mill.api.daemon.Result.Failure(problem, scriptFile.toNIO, m.getIndex)
            } else {
              mill.api.daemon.Result.Failure(
                s"Failed parsing build header in $scriptFile: " + e.getMessage
              )
            }
          case abort: upickle.core.AbortException =>
            mill.api.daemon.Result.Failure(
              s"Failed de-serializing config key ${e.jsonPath}: ${e.getCause.getCause.getMessage}",
              scriptFile.toNIO,
              abort.index
            )
          case _ =>
            mill.api.daemon.Result.Failure(
              s"$scriptFile Failed de-serializing config key ${e.jsonPath} ${e.getCause.getMessage}"
            )
        }
    }
  }

  /**
   * Iterates over the `rest` entries in a [[HeaderData]], dispatching to `onProperty`
   * for simple keys and `onNestedObject` for `object <name>:` keys. Used by both
   * code generation and precompiled module dynamic override flattening.
   */
  def processRest[T](
      scriptPath: os.Path,
      data: HeaderData
  )(
      onProperty: (Located[String], BufferedValue) => T,
      onNestedObject: (Located[String], String, HeaderData) => T
  ): Seq[T] = {
    for ((locatedKey, v) <- data.rest.toSeq)
      yield locatedKey.value.split(" +") match {
        case Array(_) => onProperty(locatedKey, v)
        case Array("object", name) =>
          val nestedData =
            try BufferedValue.transform(v, headerDataReader(scriptPath))
            catch {
              case abort: upickle.core.AbortException =>
                nestedHeaderDataError(scriptPath, abort)
            }
          onNestedObject(locatedKey, name, nestedData)
        case _ => throw new mill.api.daemon.Result.Exception(
            "",
            Some(mill.api.daemon.Result.Failure(
              "Invalid key: " + locatedKey.value,
              scriptPath.toNIO,
              locatedKey.index
            ))
          )
      }
  }
}
