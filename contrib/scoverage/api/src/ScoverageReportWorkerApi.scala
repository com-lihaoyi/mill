package mill.contrib.scoverage.api

import mill.api.Ctx

trait ScoverageReportWorkerApi {
  import ScoverageReportWorkerApi._

  @deprecated("Use other overload instead.", "Mill after 0.10.7")
  def report(
      reportType: ReportType,
      sources: Seq[os.Path],
      dataDirs: Seq[os.Path]
  )(implicit
      ctx: Ctx
  ): Unit = {
    report(reportType, sources, dataDirs, ctx.workspace)
  }

  def report(
      reportType: ReportType,
      sources: Seq[os.Path],
      dataDirs: Seq[os.Path],
      sourceRoot: os.Path
  )(implicit
      ctx: Ctx
  ): Unit = {
    // FIXME: We only call the deprecated version here, to preserve binary compatibility. Remove when appropriate.
    ctx.log.error(
      "Binary compatibility stub may cause infinite loops with StackOverflowError. You need to implement: def report(ReportType, Seq[Path], Seq[Path], os.Path): Unit"
    )
    report(reportType, sources, dataDirs)
  }
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
