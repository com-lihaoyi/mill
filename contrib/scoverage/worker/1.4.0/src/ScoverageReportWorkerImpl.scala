package mill.contrib.scoverage.worker

import mill.contrib.scoverage.api.ScoverageReportWorkerApi
import mill.eval.PathRef
import _root_.scoverage.Serializer.{coverageFile, deserialize}
import _root_.scoverage.IOUtils.{findMeasurementFiles, invoked}
import _root_.scoverage.report.ScoverageHtmlWriter
import _root_.scoverage.report.ScoverageXmlWriter
import mill.api.Ctx

private sealed trait ReportType { def folderName: String }
private case object Html extends ReportType { val folderName: String = "htmlReport" }
private case object Xml extends ReportType { val folderName: String = "xmlReport" }

class ScoverageReportWorkerImpl extends ScoverageReportWorkerApi {
  private def buildReport(sources: Seq[PathRef], dataDir: String, reportType: ReportType)(implicit  ctx: Ctx.Dest): Unit = {
    val coverageFileObj = coverageFile(dataDir)
    val coverage = deserialize(coverageFileObj)
    coverage(invoked(findMeasurementFiles(dataDir)))
    val sourceFolders = sources.map(_.path.toIO)
    val folder = ctx.dest
    os.makeDir.all(folder)
    reportType match {
      case Html =>
        new ScoverageHtmlWriter(sourceFolders, folder.toIO, None)
          .write(coverage)
      case Xml =>
        new ScoverageXmlWriter(sourceFolders, folder.toIO, false)
            .write(coverage)
    }
  }
  override def htmlReport(sources: Seq[PathRef], dataDir: String)(implicit ctx: Ctx.Dest): Unit = {
    buildReport(sources, dataDir, Html)
  }
  override def xmlReport(sources: Seq[PathRef], dataDir: String)(implicit ctx: Ctx.Dest): Unit = {
    buildReport(sources, dataDir, Xml)
  }
}
