package mill.javalib

import com.lihaoyi.unroll
import com.lumidion.sonatype.central.client.core.SonatypeCredentials
import mill.*
import javalib.*
import mill.api.{ExternalModule, Task}
import mill.util.Tasks
import mill.api.DefaultTaskModule
import mill.api.Result
import mill.javalib.MavenPublishModule.{
  defaultAwaitTimeout,
  defaultConnectTimeout,
  defaultCredentials,
  defaultReadTimeout,
  getSonatypeCredentials
}
import mill.javalib.publish.Artifact
import mill.javalib.publish.SonatypeHelpers.{PASSWORD_ENV_VARIABLE_NAME, USERNAME_ENV_VARIABLE_NAME}
import mill.api.daemon.Logger
import mill.javalib.PublishModule.PublishData

trait MavenPublishModule extends PublishModule, MavenWorkerSupport {
  def mavenConnectTimeout: T[Int] = Task { defaultConnectTimeout }

  def mavenReadTimeout: T[Int] = Task { defaultReadTimeout }

  def mavenAwaitTimeout: T[Int] = Task { defaultAwaitTimeout }

  def mavenReleaseUri: T[String]

  def mavenSnapshotUri: T[String]

  // noinspection ScalaUnusedSymbol - used as a Mill task invokable from CLI
  def publishMaven(
      username: String = defaultCredentials,
      password: String = defaultCredentials,
      @unroll sources: Boolean = true,
      @unroll docs: Boolean = true
  ): Task.Command[Unit] = Task.Command {
    val artifact = artifactMetadata()
    val credentials = getSonatypeCredentials(username, password)()
    val publishData = publishArtifactsPayload(sources = sources, docs = docs)()

    MavenPublishModule.publishAll(
      Seq(PublishData(artifact, publishData)),
      bundleName = None,
      credentials,
      awaitTimeout = mavenAwaitTimeout(),
      connectTimeout = mavenConnectTimeout(),
      readTimeout = mavenReadTimeout(),
      releaseUri = mavenReleaseUri(),
      snapshotUri = mavenSnapshotUri(),
      taskDest = Task.dest,
      log = Task.log,
      env = Task.env,
      worker = mavenWorker()
    )
  }
}

/**
 * External module to publish artifacts to maven compatible repository
 */
object MavenPublishModule extends ExternalModule with DefaultTaskModule
    with MavenWorkerSupport {

  def self = this
  val defaultCredentials: String = ""
  val defaultReadTimeout: Int = 60000
  val defaultConnectTimeout: Int = 5000
  val defaultAwaitTimeout: Int = 120 * 1000

  // Set the default command to "publishAll"
  def defaultTask(): String = "publishAll"

  def publishAll(
      publishArtifacts: mill.util.Tasks[PublishModule.PublishData] =
        Tasks.resolveMainDefault("__:PublishModule.publishArtifacts"),
      username: String = defaultCredentials,
      password: String = defaultCredentials,
      readTimeout: Int = defaultReadTimeout,
      connectTimeout: Int = defaultConnectTimeout,
      awaitTimeout: Int = defaultAwaitTimeout,
      bundleName: String = "",
      releaseUri: String,
      snapshotUri: String
  ): Command[Unit] = Task.Command {
    val artifacts = Task.sequence(publishArtifacts.value)()

    val finalBundleName = if (bundleName.isEmpty) None else Some(bundleName)
    val credentials = getSonatypeCredentials(username, password)()

    publishAll(
      artifacts,
      finalBundleName,
      credentials,
      readTimeout = readTimeout,
      connectTimeout = connectTimeout,
      awaitTimeout = awaitTimeout,
      releaseUri = releaseUri,
      snapshotUri = snapshotUri,
      taskDest = Task.dest,
      log = Task.log,
      env = Task.env,
      worker = mavenWorker()
    )
  }

  private def publishAll(
      publishArtifacts: Seq[PublishData],
      bundleName: Option[String],
      credentials: SonatypeCredentials,
      readTimeout: Int,
      connectTimeout: Int,
      awaitTimeout: Int,
      releaseUri: String,
      snapshotUri: String,
      taskDest: os.Path,
      log: Logger,
      env: Map[String, String],
      worker: internal.MavenWorkerSupport.Api
  ): Unit = {
    val dryRun = env.get("MILL_TESTS_PUBLISH_DRY_RUN").contains("1")

    def publishSnapshot(publishData: PublishData, isSnapshot: Boolean): Unit = {
      val uri = if(isSnapshot) releaseUri else snapshotUri
      val artifacts = MavenWorkerSupport.RemoteM2Publisher.asM2ArtifactsFromPublishDatas(
        publishData.meta,
        publishData.payloadAsMap
      )

      if(isSnapshot) {
        log.info(
          s"Detected a 'SNAPSHOT' version for ${publishData.meta}, publishing to Maven Repository at '$uri'"
        )
      }

      /** Maven uses this as a workspace for file manipulation. */
      val mavenWorkspace = taskDest / "maven"

      if (dryRun) {
        val publishTo = taskDest / "repository"
        val result = worker.publishToLocal(
          publishTo = publishTo,
          workspace = mavenWorkspace,
          artifacts
        )
        log.info(s"Dry-run publishing to '$publishTo' finished with result: $result")
      } else {
        val result = worker.publishToRemote(
          uri = uri,
          workspace = mavenWorkspace,
          username = credentials.username,
          password = credentials.password,
          artifacts
        )
        log.info(s"Publishing to '$uri' finished with result: $result")
      }
    }

    val (snapshots, releases) = publishArtifacts.partition(_.meta.isSnapshot)

    bundleName.filter(_ => snapshots.nonEmpty).foreach { bundleName =>
      throw new IllegalArgumentException(
        s"Publishing SNAPSHOT versions when bundle name ($bundleName) is specified is not supported.\n\n" +
          s"SNAPSHOT versions: ${pprint.apply(snapshots)}"
      )
    }

    releases.foreach { publishData => publishSnapshot(publishData, false)}
    snapshots.foreach(data => publishSnapshot(data, true))
  }

  private def getSonatypeCredential(
      credentialParameterValue: String,
      credentialName: String,
      envVariableName: String
  ): Task[String] = Task.Anon {
    if (credentialParameterValue.nonEmpty) {
      Result.Success(credentialParameterValue)
    } else {
      (for {
        credential <- Task.env.get(envVariableName)
      } yield {
        Result.Success(credential)
      }).getOrElse(
        Result.Failure(
          s"No $credentialName set. Consider using the $envVariableName environment variable or passing `$credentialName` argument"
        )
      )
    }
  }

  private def getSonatypeCredentials(
      usernameParameterValue: String,
      passwordParameterValue: String
  ): Task[SonatypeCredentials] = Task.Anon {
    val username =
      getSonatypeCredential(usernameParameterValue, "username", USERNAME_ENV_VARIABLE_NAME)()
    val password =
      getSonatypeCredential(passwordParameterValue, "password", PASSWORD_ENV_VARIABLE_NAME)()
    Result.Success(SonatypeCredentials(username, password))
  }

  lazy val millDiscover: mill.api.Discover = mill.api.Discover[this.type]
}
