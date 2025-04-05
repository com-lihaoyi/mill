package mill.scalalib.worker

import java.io.File
import java.util.Optional
import mill.api._
import mill.runner.api._

import scala.jdk.OptionConverters.RichOptional

@internal
class ZincProblemPosition(base: xsbti.Position) extends ProblemPosition {

  import ZincProblemPosition.ToIntOption

  override def line: Option[Int] = base.line().toIntOption
  override def lineContent: String = base.lineContent()
  override def offset: Option[Int] = base.offset().toIntOption
  override def pointer: Option[Int] = base.pointer().toIntOption
  override def pointerSpace: Option[String] = base.pointerSpace().toScala
  override def sourcePath: Option[String] = base.sourcePath().toScala
  override def sourceFile: Option[File] = base.sourceFile().toScala
  override def startOffset: Option[Int] = base.startOffset().toIntOption
  override def endOffset: Option[Int] = base.endOffset().toIntOption
  override def startLine: Option[Int] = base.startLine().toIntOption
  override def startColumn: Option[Int] = base.startColumn().toIntOption
  override def endLine: Option[Int] = base.endLine().toIntOption
  override def endColumn: Option[Int] = base.endColumn().toIntOption
}

object ZincProblemPosition {

  private implicit class ToIntOption(val opt: Optional[Integer]) extends AnyVal {
    def toIntOption: Option[Int] = if (opt.isPresent()) Option(opt.get().intValue()) else None
  }

}
