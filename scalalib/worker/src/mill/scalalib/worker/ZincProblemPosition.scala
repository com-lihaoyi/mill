package mill.scalalib.worker

import java.io.File
import java.util.Optional

import mill.api.{ProblemPosition, internal}

@internal
class ZincProblemPosition(base: xsbti.Position) extends ProblemPosition {

  object JavaOptionConverter {
    implicit def convertInt(x: Optional[Integer]): Option[Int] =
      if (x.isPresent) Some(x.get().intValue()) else None
    implicit def convert[T](x: Optional[T]): Option[T] = if (x.isPresent) Some(x.get()) else None
  }

  import JavaOptionConverter._

  override def line: Option[Int] = base.line()
  override def lineContent: String = base.lineContent()
  override def offset: Option[Int] = base.offset()
  override def pointer: Option[Int] = base.pointer()
  override def pointerSpace: Option[String] = base.pointerSpace()
  override def sourcePath: Option[String] = base.sourcePath()
  override def sourceFile: Option[File] = base.sourceFile()
  override def startOffset: Option[Int] = base.startOffset()
  override def endOffset: Option[Int] = base.endOffset()
  override def startLine: Option[Int] = base.startLine()
  override def startColumn: Option[Int] = base.startColumn()
  override def endLine: Option[Int] = base.endLine()
  override def endColumn: Option[Int] = base.endColumn()
}
