package mill.scalajslib.worker.api

import java.io.File

private[scalajslib] final case class Report(
    val publicModules: Iterable[Report.Module],
    val dest: File
)

private[scalajslib] object Report {
  final case class Module(
      val moduleID: String,
      val jsFileName: String,
      val sourceMapName: Option[String],
      val moduleKind: ModuleKind
  )
}
