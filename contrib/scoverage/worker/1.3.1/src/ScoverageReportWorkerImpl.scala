package mill.contrib.scoverage.worker

import mill.contrib.scoverage.api.ScoverageReportWorkerApi
import mill.eval.PathRef
import _root_.scoverage.Serializer.{ coverageFile, deserialize }
import _root_.scoverage.IOUtils.{ findMeasurementFiles, invoked }
import _root_.scoverage.report.ScoverageHtmlWriter

class ScoverageReportWorkerImpl(sources: Seq[PathRef]) extends ScoverageReportWorkerApi {
    def htmlReport(dataDir: String, selfDir: String) = {
      val coverageFileObj = coverageFile(dataDir)
      val coverage = deserialize(coverageFileObj)
      coverage(invoked(findMeasurementFiles(dataDir)))
      val Seq(PathRef(sourceFolderPath, _, _)) = sources
      val sourceFolders = Seq(sourceFolderPath.toIO)
      val htmlFolder = new java.io.File(s"${selfDir}/htmlReport")
      htmlFolder.mkdir()
      new ScoverageHtmlWriter(sourceFolders, htmlFolder, None)
        .write(coverage)
    }
}
