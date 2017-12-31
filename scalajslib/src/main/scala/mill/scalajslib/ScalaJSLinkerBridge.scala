package mill.scalajslib

import java.io.File

sealed trait OptimizeMode

object FastOpt extends OptimizeMode
object FullOpt extends OptimizeMode

trait ScalaJSLinkerBridge {
  def link(sources: Seq[File], libraries: Seq[File], dest: File, main: Option[String], mode: OptimizeMode): Unit
}
