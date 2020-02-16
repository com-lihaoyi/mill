package mill.contrib.scoverage.api

import mill.api.Ctx

trait ScoverageReportWorkerApi {
  import ScoverageReportWorkerApi._

  def report(reportType: ReportType, sources: Seq[os.Path], dataDir: os.Path)(implicit ctx: Ctx.Dest): Unit
}

object ScoverageReportWorkerApi {
  sealed trait ReportType { def folderName: String }
  object ReportType {
    final case object Html extends ReportType { val folderName: String = "htmlReport" }
    final case object Xml extends ReportType { val folderName: String = "xmlReport" }
  }
}

