package mill.contrib.scoverage.worker

import mill.contrib.scoverage.api.ScoverageReportWorkerApi
import _root_.scoverage.Serializer.{coverageFile, deserialize}
import _root_.scoverage.IOUtils.{findMeasurementFiles, invoked}
import _root_.scoverage.report.ScoverageHtmlWriter
import _root_.scoverage.report.ScoverageXmlWriter
import mill.api.Ctx
import mill.contrib.scoverage.api.ScoverageReportWorkerApi.ReportType

class ScoverageReportWorkerImpl extends ScoverageReportWorkerApi {

  override def report(reportType: ReportType, sources: Seq[os.Path], dataDir: os.Path)(implicit  ctx: Ctx.Dest): Unit = {
    val coverageFileObj = coverageFile(dataDir.toIO)
    val coverage = deserialize(coverageFileObj)
    coverage(invoked(findMeasurementFiles(dataDir.toIO)))
    val sourceFolders = sources.map(_.toIO)
    val folder = ctx.dest
    os.makeDir.all(folder)
    reportType match {
      case ReportType.Html =>
        new ScoverageHtmlWriter(sourceFolders, folder.toIO, None)
          .write(coverage)
      case ReportType.Xml =>
        new ScoverageXmlWriter(sourceFolders, folder.toIO, false)
            .write(coverage)
    }
  }
}
