package mill.mima

import mill._
import mill.scalalib._

private[mima] trait VersionSpecific extends CoursierModule {
  private[mima] def mimaWorkerClasspath: T[Agg[PathRef]] = T {
    Lib
      .resolveDependencies(
        repositoriesTask(),
        Agg(
          ivy"com.lihaoyi:mill-mima-worker-impl_2.13:${BuildInfo.millVersion}"
            .exclude("com.lihaoyi" -> "mill-mima-worker-api_2.13")
        ).map(Lib.depToBoundDep(_, BuildInfo.scalaVersion)),
        ctx = Some(T.log)
      )
  }
}
