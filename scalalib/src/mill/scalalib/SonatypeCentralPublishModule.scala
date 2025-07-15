package mill.scalalib

import com.lumidion.sonatype.central.client.core.{PublishingType, SonatypeCredentials}
import mill._
import scalalib._
import define.{ExternalModule, Task}
import mill.main.Tasks
import mill.define.TaskModule
import mill.api.{Result, experimental}
import mill.scalalib.SonatypeCentralPublishModule.{
  defaultAwaitTimeout,
  defaultConnectTimeout,
  defaultCredentials,
  defaultReadTimeout,
  getPublishingTypeFromReleaseFlag,
  getSonatypeCredentials
}
import mill.scalalib.publish.Artifact
import mill.scalalib.publish.SonatypeHelpers.{
  PASSWORD_ENV_VARIABLE_NAME,
  USERNAME_ENV_VARIABLE_NAME
}

@experimental
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
  ): define.Command[Unit] =
    Task.Command {
      val publishData = publishArtifacts()
      val artifact = publishData.meta
      val finalCredentials = getSonatypeCredentials(username, password)()

      def publishSnapshot(): Unit = {
        val uri = sonatypeSnapshotUri
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
        val fileMapping = publishData.withConcretePath._1
        PublishModule.pgpImportSecretIfProvided(Task.env)
        val publisher = new SonatypeCentralPublisher(
          credentials = finalCredentials,
          gpgArgs = sonatypeCentralGpgArgs().split(",").toIndexedSeq,
          connectTimeout = sonatypeCentralConnectTimeout(),
          readTimeout = sonatypeCentralReadTimeout(),
          log = Task.log,
          workspace = Task.workspace,
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
      if (artifact.isSnapshot) publishSnapshot()
      else publishRelease()
    }
}

object SonatypeCentralPublishModule extends ExternalModule with TaskModule {

  val defaultCredentials: String = ""
  val defaultReadTimeout: Int = 60000
  val defaultConnectTimeout: Int = 5000
  val defaultAwaitTimeout: Int = 120 * 1000
  val defaultShouldRelease: Boolean = true

  // Set the default command to "publishAll"
  def defaultCommandName(): String = "publishAll"

  def publishAll(
      publishArtifacts: Tasks[PublishModule.PublishData] =
        Tasks.resolveMainDefault("__.publishArtifacts"),
      username: String = defaultCredentials,
      password: String = defaultCredentials,
      shouldRelease: Boolean = defaultShouldRelease,
      gpgArgs: String = "",
      readTimeout: Int = defaultReadTimeout,
      connectTimeout: Int = defaultConnectTimeout,
      awaitTimeout: Int = defaultAwaitTimeout,
      bundleName: String = ""
  ): Command[Unit] = Task.Command {

    val artifacts: Seq[(Seq[(os.Path, String)], Artifact)] =
      Task.sequence(publishArtifacts.value)().map {
        case data @ PublishModule.PublishData(_, _) => data.withConcretePath
      }

    val (snapshotArtifacts, releaseArtifacts) = artifacts.partition(_._2.isSnapshot)
    val log = Task.log

    if (snapshotArtifacts.nonEmpty) {
      val commonMessage =
        "\n" +
          "Please extend `SonatypeCentralPublishModule` and use its `publishSonatypeCentral` task to publish " +
          "snapshots.\n" +
          "\n" +
          s"Found the following SNAPSHOT artifacts: ${pprint.apply(snapshotArtifacts)}"

      if (releaseArtifacts.isEmpty) {
        // We can not do anything here because we need more metadata about the published files than `artifacts` provide.
        throw new IllegalArgumentException(
          "All artifacts to publish are SNAPSHOTs, but publishing SNAPSHOTs to Sonatype Central is not " +
            s"supported with this task.\n" +
            commonMessage
        )
      } else {
        log.error(
          "Some of the artifacts to publish are SNAPSHOTs, but publishing SNAPSHOTs to Sonatype Central is not " +
            s"supported with this task. SNAPSHOT artifacts will be skipped.\n" +
            commonMessage
        )
      }
    }

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
      workspace = Task.workspace,
      env = Task.env,
      awaitTimeout = awaitTimeout
    )
    publisher.publishAll(
      getPublishingTypeFromReleaseFlag(shouldRelease),
      finalBundleName,
      artifacts: _*
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

  lazy val millDiscover: mill.define.Discover = mill.define.Discover[this.type]
}
