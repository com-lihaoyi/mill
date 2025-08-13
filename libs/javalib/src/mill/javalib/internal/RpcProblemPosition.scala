package mill.javalib.internal

import mill.api.daemon.internal.ProblemPosition
import mill.api.JsonFormatters.*

import java.io.File

/** [[ProblemPosition]] that can be sent over RPC. */
case class RpcProblemPosition(
    line: Option[Int],
    lineContent: String,
    offset: Option[Int],
    pointer: Option[Int],
    pointerSpace: Option[String],
    sourcePath: Option[String],
    sourceFile: Option[File],
    startOffset: Option[Int],
    endOffset: Option[Int],
    startLine: Option[Int],
    startColumn: Option[Int],
    endLine: Option[Int],
    endColumn: Option[Int]
) extends ProblemPosition derives upickle.default.ReadWriter
object RpcProblemPosition {
  def apply(p: ProblemPosition): RpcProblemPosition = apply(
    line = p.line,
    lineContent = p.lineContent,
    offset = p.offset,
    pointer = p.pointer,
    pointerSpace = p.pointerSpace,
    sourcePath = p.sourcePath,
    sourceFile = p.sourceFile,
    startOffset = p.startOffset,
    endOffset = p.endOffset,
    startLine = p.startLine,
    startColumn = p.startColumn,
    endLine = p.endLine,
    endColumn = p.endColumn
  )
}
