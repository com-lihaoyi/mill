package mill.contrib.scoverage.api

import mill.api.Ctx
import mill.eval.PathRef

trait ScoverageReportWorkerApi {
  def htmlReport(sources: Seq[PathRef], dataDir: String)(implicit ctx: Ctx.Dest): Unit
  def xmlReport(sources: Seq[PathRef], dataDir: String)(implicit ctx: Ctx.Dest): Unit
}
