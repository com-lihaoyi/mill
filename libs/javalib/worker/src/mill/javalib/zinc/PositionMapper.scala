package mill.javalib.zinc

import mill.constants.CodeGenConstants
import xsbti.VirtualFile

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

object PositionMapper {

  import sbt.util.InterfaceUtil

  private val userCodeStartMarker = "//SOURCECODE_ORIGINAL_CODE_START_MARKER"

  /** Transforms positions of problems if coming from a build file. */
  private def lookup(buildSources: Map[String, xsbti.Position => xsbti.Position])(
    oldPos: xsbti.Position
  ): xsbti.Position = {
    val src = oldPos.sourcePath()
    if src.isPresent then {
      buildSources.get(src.get()) match {
        case Some(f) => f(oldPos)
        case _ => oldPos
      }
    } else {
      oldPos
    }
  }

  def create(sources: Array[VirtualFile])
  : (Map[os.Path, os.Path], Option[xsbti.Position => xsbti.Position]) = {
    val buildSources0 = {
      def isBuild(vf: VirtualFile) =
        CodeGenConstants.buildFileExtensions.asScala.exists(ex =>
          vf.id().endsWith(s".$ex")
        )

      sources.collect({
        case vf if isBuild(vf) =>
          val str = new String(vf.input().readAllBytes(), StandardCharsets.UTF_8)

          val lines = str.linesWithSeparators.toVector
          val adjustedFile = lines
            .collectFirst { case s"//SOURCECODE_ORIGINAL_FILE_PATH=$rest" => rest.trim }
            .getOrElse(sys.error(vf.id()))

          (vf.id(), adjustedFile, remap(lines, adjustedFile))
      })
    }

    val map = buildSources0
      .map {
        case (generated, original, _) =>
          os.Path(generated) -> os.Path(original)
      }
      .toMap
    val lookupOpt = Option.when(buildSources0.nonEmpty) {
      lookup(buildSources0.map { case (generated, _, f) => (generated, f) }.toMap)
    }
    (map, lookupOpt)
  }

  private def remap(
    lines: Vector[String],
    adjustedFile: String
  ): xsbti.Position => xsbti.Position = {
    val markerLine = lines.indexWhere(_.startsWith(userCodeStartMarker))

    val topWrapperLen = lines.take(markerLine + 1).map(_.length).sum

    val originPath = Some(adjustedFile)
    val originFile = Some(java.nio.file.Paths.get(adjustedFile).toFile)

    def userCode(offset: java.util.Optional[Integer]): Boolean =
      intValue(offset, -1) > topWrapperLen

    def inner(pos0: xsbti.Position): xsbti.Position = {
      if userCode(pos0.startOffset()) || userCode(pos0.offset()) then {
        val IArray(line, offset, startOffset, endOffset, startLine, endLine) =
          IArray(
            pos0.line(),
            pos0.offset(),
            pos0.startOffset(),
            pos0.endOffset(),
            pos0.startLine(),
            pos0.endLine()
          )
            .map(intValue(_, 1) - 1)

        val (baseLine, baseOffset) = (markerLine, topWrapperLen)

        InterfaceUtil.position(
          line0 = Some(line - baseLine),
          content = pos0.lineContent(),
          offset0 = Some(offset - baseOffset),
          pointer0 = InterfaceUtil.jo2o(pos0.pointer()),
          pointerSpace0 = InterfaceUtil.jo2o(pos0.pointerSpace()),
          sourcePath0 = originPath,
          sourceFile0 = originFile,
          startOffset0 = Some(startOffset - baseOffset),
          endOffset0 = Some(endOffset - baseOffset),
          startLine0 = Some(startLine - baseLine),
          startColumn0 = InterfaceUtil.jo2o(pos0.startColumn()),
          endLine0 = Some(endLine - baseLine),
          endColumn0 = InterfaceUtil.jo2o(pos0.endColumn())
        )
      } else {
        pos0
      }
    }

    inner
  }
}
