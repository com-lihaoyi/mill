package mill.init.importer
package sbt

import upickle.default.{ReadWriter, macroRW}

case class SbtProjectIR(
    projectId: String,
    moduleName: String,
    baseDir: os.SubPath,
    crossPlatformBaseDir: Option[os.SubPath],
    crossScalaVersions: Seq[String],
    mainModuleIRs: Seq[ModuleIR],
    testModuleIRs: Seq[ModuleIR]
)
object SbtProjectIR {
  implicit val rw: ReadWriter[SbtProjectIR] = macroRW
}
