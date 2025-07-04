package mill.init.importer
package sbt

import upickle.default.{ReadWriter, macroRW}

case class ExportedSbtProject(
    projectId: String,
    moduleName: String,
    baseDir: os.SubPath,
    crossPlatformBaseDir: Option[os.SubPath],
    crossScalaVersions: Seq[String],
    mainModuleIRs: Seq[ModuleIR],
    testModuleIRs: Seq[ModuleIR]
)
object ExportedSbtProject {
  implicit val rw: ReadWriter[ExportedSbtProject] = macroRW
}
