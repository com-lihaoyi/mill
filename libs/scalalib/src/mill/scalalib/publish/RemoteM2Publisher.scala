package mill.scalalib.publish

import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.artifact.{DefaultArtifact, Artifact as M2Artifact}
import org.eclipse.aether.deployment.{DeployRequest, DeployResult}
import org.eclipse.aether.repository.{LocalRepository, RemoteRepository}
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.util.repository.AuthenticationBuilder

import scala.annotation.nowarn
import scala.util.Using

object RemoteM2Publisher {
  def publish(
      uri: String,
      workspace: os.Path,
      username: String,
      password: String,
      artifacts: IterableOnce[M2Artifact]
  ): DeployResult = {
    Using.Manager { use =>
      val system = use(RepositorySystemSupplier().get())
      @nowarn(
        "msg=deprecated"
      ) // The suggested `system.createSessionBuilder` throws classload errors for some reason
      val session = DefaultRepositorySystemSession()
      val localRepository = LocalRepository(workspace.toNIO)
      val localRepositoryManager = system.newLocalRepositoryManager(session, localRepository)
      session.setLocalRepositoryManager(localRepositoryManager)
      val authentication =
        AuthenticationBuilder().addUsername(username).addPassword(password).build()
      val remoteRepository = RemoteRepository.Builder("central-snapshots", "default", uri)
        .setAuthentication(authentication).build()

      val deployRequest = DeployRequest().setRepository(remoteRepository)
      artifacts.iterator.foreach(deployRequest.addArtifact)

      system.deploy(session, deployRequest)
    }.get
  }

  def asM2Artifact(pom: os.Path, artifact: Artifact): DefaultArtifact =
    DefaultArtifact(artifact.group, artifact.id, null, "pom", artifact.version, null, pom.toIO)

  def asM2Artifact(info: PublishInfo, artifact: Artifact): DefaultArtifact =
    DefaultArtifact(
      artifact.group,
      artifact.id,
      info.classifier.orNull,
      info.ext,
      artifact.version,
      null,
      info.file.path.toIO
    )

  def asM2Artifacts(
      pom: os.Path,
      artifact: Artifact,
      publishInfos: IterableOnce[PublishInfo]
  ): List[DefaultArtifact] =
    asM2Artifact(pom, artifact) +: publishInfos.iterator.map(asM2Artifact(_, artifact)).toList
}
