package mill.contrib.scoverage.api

import mill.eval.PathRef

trait ScoverageReportWorkerApi {
  def htmlReport(sources: Seq[PathRef], dataDir: String, selfDir: String): Unit
  def xmlReport(sources: Seq[PathRef], dataDir: String, selfDir: String): Unit
}
