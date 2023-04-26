package mill.mima

import mill._
import mill.scalalib._

private[mima] trait VersionSpecific extends CoursierModule {
  private[mima] def mimaWorkerClasspath: T[Agg[PathRef]] = T {
    mill.modules.Util.millProjectModule(
      "MILL_MIMA_WORKER_IMPL",
      "mill-mima-worker-impl",
      repositoriesTask()
    )
  }
}
