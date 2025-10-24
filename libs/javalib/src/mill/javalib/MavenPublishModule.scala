package mill.javalib

import com.lihaoyi.unroll
import mill.*
import mill.api.*
import mill.javalib.PublishModule.PublishData
import mill.util.Tasks

trait MavenPublishModule extends PublishModule, MavenWorkerSupport, SonatypeCredentialsModule, MavenPublish {

  def mavenReleaseUri: T[String]

  def mavenSnapshotUri: T[String]

  def publishMaven(
      username: String = "",
      password: String = "",
      @unroll sources: Boolean = true,
      @unroll docs: Boolean = true
  ): Task.Command[Unit] = Task.Command {
    val artifact = artifactMetadata()
    val credentials = getSonatypeCredentials(username, password)()
    val publishData = publishArtifactsPayload(sources = sources, docs = docs)()

    mavenPublishDatas(
      Seq(PublishData(artifact, publishData)),
      bundleName = None,
      credentials,
      releaseUri = mavenReleaseUri(),
      snapshotUri = mavenSnapshotUri(),
      taskDest = Task.dest,
      log = Task.log,
      env = Task.env,
      worker = mavenWorker()
    )
  }

}

object MavenPublishModule extends ExternalModule, DefaultTaskModule, MavenWorkerSupport, SonatypeCredentialsModule, MavenPublish {

  def defaultTask(): String = "publishAll"

  def publishAll(
      publishArtifacts: mill.util.Tasks[PublishModule.PublishData] =
        Tasks.resolveMainDefault("__:PublishModule.publishArtifacts"),
      username: String = "",
      password: String = "",
      bundleName: String = "",
      releaseUri: String,
      snapshotUri: String
  ): Command[Unit] = Task.Command {
    val artifacts = Task.sequence(publishArtifacts.value)()

    val finalBundleName = if (bundleName.isEmpty) None else Some(bundleName)
    val credentials = getSonatypeCredentials(username, password)()

    mavenPublishDatas(
      artifacts,
      finalBundleName,
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