package mill.contrib.scoverage.api

import mill.api.Ctx

trait ScoverageReportWorkerApi {
  import ScoverageReportWorkerApi._

  def report(reportType: ReportType, sources: Seq[os.Path], dataDirs: Seq[os.Path])(implicit ctx: Ctx): Unit
}

object ScoverageReportWorkerApi {
  sealed trait ReportType
  sealed trait FileReportType extends ReportType { def folderName: String }
  object ReportType {
    final case object Html extends FileReportType { val folderName: String = "htmlReport" }
    final case object Xml extends FileReportType { val folderName: String = "xmlReport" }
    final case object Console extends ReportType
  }
}

