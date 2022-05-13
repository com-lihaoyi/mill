package mill.scalajslib.internal

import mill.api.internal
import mill.PathRef
import mill.scalajslib.api.Report

@internal
private[scalajslib] object ScalaJSUtils {
  def getReportMainFilePath(report: Report): os.Path =
    report.dest.path / report.publicModules.head.jsFileName
  def getReportMainFilePathRef(report: Report): PathRef =
    PathRef(getReportMainFilePath(report))
}
