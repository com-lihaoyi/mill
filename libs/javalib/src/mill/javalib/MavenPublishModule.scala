package mill.javalib

import mill.*
import mill.api.*
import mill.util.Tasks

/**
 * External module to publish artifactes to Maven repositories other than `central.sonatype.org`
 * (e.g. a private Maven repository).
 */
object MavenPublishModule extends ExternalModule, DefaultTaskModule, MavenWorkerSupport,
      PublishCredentialsModule, MavenPublish {

  def defaultTask(): String = "publishAll"

  def publishAll(
      publishArtifacts: mill.util.Tasks[PublishModule.PublishData] =
        Tasks.resolveMainDefault("__:PublishModule.publishArtifacts"),
      username: String = "",
      password: String = "",
      releaseUri: String,
      snapshotUri: String
  ): Command[Unit] = Task.Command {
    val artifacts = Task.sequence(publishArtifacts.value)()

    val credentials = getPublishCredentials("MILL_MAVEN", username, password)()

    mavenPublishDatas(
      artifacts,
      credentials,
      releaseUri = releaseUri,
      snapshotUri = snapshotUri,
      taskDest = Task.dest,
      log = Task.log,
      env = Task.env,
      worker = mavenWorker()
    )
  }

  lazy val millDiscover: Discover = Discover[this.type]

}
