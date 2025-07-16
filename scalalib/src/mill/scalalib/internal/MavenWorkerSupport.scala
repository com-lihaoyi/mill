package mill.scalalib.internal

import mill.api.internal
import mill.scalalib.MavenWorkerSupport.RemoteM2Publisher

@internal
object MavenWorkerSupport {
  trait Api extends mill.scalalib.MavenWorkerSupport.Api {

    /** Publishes artifacts to a local Maven repository. */
    def publishToLocal(
        publishTo: os.Path,
        workspace: os.Path,
        artifacts: IterableOnce[RemoteM2Publisher.M2Artifact]
    ): RemoteM2Publisher.DeployResult
  }
}
