package mill.javalib.internal

import mill.api.daemon.internal.internal
import mill.javalib.MavenWorkerSupport.RemoteM2Publisher

@internal
object MavenWorkerSupport {
  trait Api extends  mill.javalib.MavenWorkerSupport.Api {
    /** Publishes artifacts to a local Maven repository. */
    def publishToLocal(
      publishTo: os.Path,
      workspace: os.Path,
      artifacts: IterableOnce[RemoteM2Publisher.M2Artifact],
    ): RemoteM2Publisher.DeployResult
  }
}
