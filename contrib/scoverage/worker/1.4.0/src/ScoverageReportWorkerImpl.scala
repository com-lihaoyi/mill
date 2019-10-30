package mill.contrib.scoverage.worker

import mill.contrib.scoverage.api.ScoverageReportWorkerApi
import mill.api.PathRef
import _root_.scoverage.Serializer.{ coverageFile, deserialize }
import _root_.scoverage.IOUtils.{ findMeasurementFiles, invoked }
import _root_.scoverage.report.ScoverageHtmlWriter
import _root_.scoverage.report.ScoverageXmlWriter

private sealed trait ReportType { def folderName: String }
private case object Html extends ReportType { val folderName: String = "htmlReport" }
private case object Xml extends ReportType { val folderName: String = "xmlReport" }

class ScoverageReportWorkerImpl extends ScoverageReportWorkerApi {
  private def buildReport(sources: Seq[PathRef], dataDir: String, selfDir: String, reportType: ReportType) = {
    val coverageFileObj = coverageFile(dataDir)
    val coverage = deserialize(coverageFileObj)
    coverage(invoked(findMeasurementFiles(dataDir)))
    val sourceFolders = sources.map(_.path.toIO)
    val folder = new java.io.File(s"${selfDir}/${reportType.folderName}")
    folder.mkdir()
    reportType match {
      case Html =>
        new ScoverageHtmlWriter(sourceFolders, folder, None)
          .write(coverage)
      case Xml =>
        new ScoverageXmlWriter(sourceFolders, folder, false)
            .write(coverage)
    }
  }
  def htmlReport(sources: Seq[PathRef], dataDir: String, selfDir: String) = {
    buildReport(sources, dataDir, selfDir, Html)
  }
  def xmlReport(sources: Seq[PathRef], dataDir: String, selfDir: String) = {
    buildReport(sources, dataDir, selfDir, Xml)
  }
}
