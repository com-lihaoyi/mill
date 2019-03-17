package mill.contrib.scoverage.api

trait ScoverageReportWorkerApi {
  def htmlReport(dataDir: String, selfDir: String): Unit
}
