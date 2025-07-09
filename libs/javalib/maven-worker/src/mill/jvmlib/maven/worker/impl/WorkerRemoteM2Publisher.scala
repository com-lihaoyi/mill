package mill.javalib.maven.worker.impl

import mill.javalib.MavenWorkerSupport.RemoteM2Publisher
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.artifact.{DefaultArtifact, Artifact as M2Artifact}
import org.eclipse.aether.deployment.{DeployRequest, DeployResult}
import org.eclipse.aether.repository.{LocalRepository, RemoteRepository}
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.util.repository.AuthenticationBuilder

import scala.annotation.nowarn
import scala.util.Using

object WorkerRemoteM2Publisher {

  /**
   * Publishes artifacts to a Maven API compatible repositories.
   *
   * Maven API compatible means it's a HTTP server that accepts PUT requests with certain headers to store files.
   * We use Eclipse Aether to deal with that.
   */
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

  def asM2Artifact(artifact: RemoteM2Publisher.M2Artifact): DefaultArtifact =
    artifact match {
      case RemoteM2Publisher.M2Artifact.POM(pom, artifact) =>
        DefaultArtifact(artifact.group, artifact.id, null, "pom", artifact.version, null, pom.toIO)

      case RemoteM2Publisher.M2Artifact.Default(info, artifact) =>
        DefaultArtifact(
          artifact.group,
          artifact.id,
          info.classifier.orNull,
          info.ext,
          artifact.version,
          null,
          info.file.path.toIO
        )
    }
}
