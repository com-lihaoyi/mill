package mill.javalib

import com.lumidion.sonatype.central.client.core.{PublishingType, SonatypeCredentials}
import mill.*
import javalib.*
import mill.api.{ExternalModule, Task}
import mill.util.Tasks
import mill.api.DefaultTaskModule
import mill.api.{Result, experimental}
import mill.javalib.SonatypeCentralPublishModule.{
  defaultAwaitTimeout,
  defaultConnectTimeout,
  defaultCredentials,
  defaultReadTimeout,
  getPublishingTypeFromReleaseFlag,
  getSonatypeCredentials
}
import mill.javalib.publish.Artifact
import mill.javalib.publish.SonatypeHelpers.{PASSWORD_ENV_VARIABLE_NAME, USERNAME_ENV_VARIABLE_NAME}
import mill.api.BuildCtx

trait SonatypeCentralPublishModule extends PublishModule with MavenWorkerSupport {
  def sonatypeCentralGpgArgs: T[String] = Task {
    PublishModule.defaultGpgArgsForPassphrase(Task.env.get("MILL_PGP_PASSPHRASE")).mkString(",")
  }

  def sonatypeCentralConnectTimeout: T[Int] = Task { defaultConnectTimeout }

  def sonatypeCentralReadTimeout: T[Int] = Task { defaultReadTimeout }

  def sonatypeCentralAwaitTimeout: T[Int] = Task { defaultAwaitTimeout }

  def sonatypeCentralShouldRelease: T[Boolean] = Task { true }

  def publishSonatypeCentral(
      username: String = defaultCredentials,
      password: String = defaultCredentials
  ): Task.Command[Unit] = Task.Command {
    val artifact = artifactMetadata()
    val finalCredentials = getSonatypeCredentials(username, password)()

    def publishSnapshot(): Unit = {
      val uri = sonatypeCentralSnapshotUri
      val artifacts = MavenWorkerSupport.RemoteM2Publisher.asM2Artifacts(
        pom().path,
        artifact,
        defaultPublishInfos()
      )

      Task.log.info(
        s"Detected a 'SNAPSHOT' version, publishing to Sonatype Central Snapshots at '$uri'"
      )
      val worker = mavenWorker()
      val result = worker.publishToRemote(
        uri = uri,
        workspace = Task.dest / "maven",
        username = finalCredentials.username,
        password = finalCredentials.password,
        artifacts
      )
      Task.log.info(s"Deployment to '$uri' finished with result: $result")
    }

    def publishRelease(): Unit = {
      val publishData = publishArtifacts()
      val fileMapping = publishData.withConcretePath._1
      val artifact = publishData.meta
      val finalCredentials = getSonatypeCredentials(username, password)()
      PublishModule.pgpImportSecretIfProvided(Task.env)
      val publisher = new SonatypeCentralPublisher(
        credentials = finalCredentials,
        gpgArgs = sonatypeCentralGpgArgs().split(",").toIndexedSeq,
        connectTimeout = sonatypeCentralConnectTimeout(),
        readTimeout = sonatypeCentralReadTimeout(),
        log = Task.log,
        workspace = BuildCtx.workspaceRoot,
        env = Task.env,
        awaitTimeout = sonatypeCentralAwaitTimeout()
      )
      publisher.publish(
        fileMapping,
        artifact,
        getPublishingTypeFromReleaseFlag(sonatypeCentralShouldRelease())
      )
    }

    // The snapshot publishing does not use the same API as release publishing.
    if (artifact.version.endsWith("SNAPSHOT")) publishSnapshot()
    else publishRelease()
  }
}

/**
 * External module to publish artifacts to `central.sonatype.org`
 */
object SonatypeCentralPublishModule extends ExternalModule with DefaultTaskModule {

  def self = this
  val defaultCredentials: String = ""
  val defaultReadTimeout: Int = 60000
  val defaultConnectTimeout: Int = 5000
  val defaultAwaitTimeout: Int = 120 * 1000
  val defaultShouldRelease: Boolean = true

  // Set the default command to "publishAll"
  def defaultTask(): String = "publishAll"

  def publishAll(
      publishArtifacts: mill.util.Tasks[PublishModule.PublishData] =
        Tasks.resolveMainDefault("__:PublishModule.publishArtifacts"),
      username: String = defaultCredentials,
      password: String = defaultCredentials,
      shouldRelease: Boolean = defaultShouldRelease,
      gpgArgs: String = "",
      readTimeout: Int = defaultReadTimeout,
      connectTimeout: Int = defaultConnectTimeout,
      awaitTimeout: Int = defaultAwaitTimeout,
      bundleName: String = ""
  ): Command[Unit] = Task.Command {

    val artifacts =
      Task.sequence(publishArtifacts.value)().map(_.withConcretePath)

    val finalBundleName = if (bundleName.isEmpty) None else Some(bundleName)
    val finalCredentials = getSonatypeCredentials(username, password)()
    PublishModule.pgpImportSecretIfProvided(Task.env)
    val publisher = new SonatypeCentralPublisher(
      credentials = finalCredentials,
      gpgArgs = gpgArgs match {
        case "" => PublishModule.defaultGpgArgsForPassphrase(Task.env.get("MILL_PGP_PASSPHRASE"))
        case gpgArgs => gpgArgs.split(",").toIndexedSeq
      },
      connectTimeout = connectTimeout,
      readTimeout = readTimeout,
      log = Task.log,
      workspace = BuildCtx.workspaceRoot,
      env = Task.env,
      awaitTimeout = awaitTimeout
    )
    Task.ctx().log.info(s"artifacts ${pprint.apply(artifacts)}")
    publisher.publishAll(
      getPublishingTypeFromReleaseFlag(shouldRelease),
      finalBundleName,
      artifacts*
    )
  }

  private def getPublishingTypeFromReleaseFlag(shouldRelease: Boolean): PublishingType = {
    if (shouldRelease) {
      PublishingType.AUTOMATIC
    } else {
      PublishingType.USER_MANAGED
    }
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
