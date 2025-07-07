package mill.jvmlib.maven.worker.impl

import mill.javalib.MavenWorkerSupport
import mill.javalib.MavenWorkerSupport.RemoteM2Publisher
import scala.jdk.CollectionConverters.*

//noinspection ScalaUnusedSymbol - invoked dynamically as a worker.
class WorkerImpl extends MavenWorkerSupport.Api {
  def publishToRemote(
    uri: String,
    workspace: os.Path,
    username: String,
    password: String,
    artifacts: IterableOnce[RemoteM2Publisher.M2Artifact]
  ): RemoteM2Publisher.DeployResult = {
    val deployResult = WorkerRemoteM2Publisher.publish(
      uri = uri, workspace = workspace, username = username, password = password,
      artifacts = artifacts.iterator.map(WorkerRemoteM2Publisher.asM2Artifact)
    )

    RemoteM2Publisher.DeployResult(
      artifacts = deployResult.getArtifacts.iterator().asScala.map(_.toString).toVector,
      metadatas = deployResult.getMetadata.iterator().asScala.map(_.toString).toVector
    )
  }
}
