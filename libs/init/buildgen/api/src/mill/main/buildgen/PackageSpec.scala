package mill.main.buildgen

import upickle.default.{ReadWriter, macroRW}

/**
 * Specification for generating source file for a Mill build package.
 * @param segments Path of the file relative to the workspace.
 * @param module Root module of this package.
 */
case class PackageSpec(segments: Seq[String], module: ModuleSpec)
object PackageSpec {
  implicit val rw: ReadWriter[PackageSpec] = macroRW
}
