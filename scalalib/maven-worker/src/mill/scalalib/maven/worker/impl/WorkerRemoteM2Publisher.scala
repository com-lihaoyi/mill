package mill.scalalib.maven.worker.impl

import mill.scalalib.MavenWorkerSupport.RemoteM2Publisher
import org.eclipse.aether.{DefaultRepositorySystemSession, RepositorySystem}
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
    setupPublishAndRun(workspace, artifacts) { (system, session, deployRequest) =>
      val authentication =
        new AuthenticationBuilder().addUsername(username).addPassword(password).build()
      val remoteRepository = new RemoteRepository.Builder("central-snapshots", "default", uri)
        .setAuthentication(authentication).build()
      deployRequest.setRepository(remoteRepository)

      system.deploy(session, deployRequest)
    }
  }

  /**
   * Publishes artifacts to a local directory.
   */
  def publishLocal(
    publishTo: os.Path,
    workspace: os.Path,
    artifacts: IterableOnce[M2Artifact]
  ): DeployResult = {
    setupPublishAndRun(workspace, artifacts) { (system, session, deployRequest) =>
      val remoteRepository =
        new RemoteRepository.Builder("local", "default", s"file://$publishTo").build()
      deployRequest.setRepository(remoteRepository)

      system.deploy(session, deployRequest)
    }
  }

  /**
   * Sets up a session and a deploy request and runs the given function.
   */
  def setupPublishAndRun[A](
    workspace: os.Path,
    artifacts: IterableOnce[M2Artifact]
  )(run: (RepositorySystem, DefaultRepositorySystemSession, DeployRequest) => A): A = {
    Using.Manager { use =>
      val system = use(new RepositorySystemSupplier().get())
      @nowarn(
        "msg=deprecated"
      ) // The suggested `system.createSessionBuilder` throws classload errors for some reason
      val session = new DefaultRepositorySystemSession()
      val localRepository = new LocalRepository(workspace.toNIO)
      val localRepositoryManager = system.newLocalRepositoryManager(session, localRepository)
      session.setLocalRepositoryManager(localRepositoryManager)

      val deployRequest = new DeployRequest()
      artifacts.iterator.foreach(deployRequest.addArtifact)

      run(system, session, deployRequest)
    }.get
  }

  def asM2Artifact(artifact: RemoteM2Publisher.M2Artifact): DefaultArtifact =
    artifact match {
      case RemoteM2Publisher.M2Artifact.POM(pom, artifact) =>
        new DefaultArtifact(artifact.group, artifact.id, null, "pom", artifact.version, null, pom.toIO)

      case RemoteM2Publisher.M2Artifact.Default(info, artifact) =>
        new DefaultArtifact(
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
