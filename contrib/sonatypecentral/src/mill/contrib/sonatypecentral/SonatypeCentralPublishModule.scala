package mill.contrib.sonatypecentral

import com.lumidion.sonatype.central.client.core.{PublishingType, SonatypeCredentials}
import mill._
import scalalib._
import define.{ExternalModule, Task}
import mill.api.Result
import mill.contrib.sonatypecentral.SonatypeCentralPublishModule.{
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

trait SonatypeCentralPublishModule extends PublishModule {
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
}

object SonatypeCentralPublishModule extends ExternalModule {

  val defaultCredentials: String = ""
  val defaultReadTimeout: Int = 60000
  val defaultConnectTimeout: Int = 5000
  val defaultAwaitTimeout: Int = 120 * 1000
  val defaultShouldRelease: Boolean = true

  def publishAll(
      publishArtifacts: mill.util.Tasks[PublishModule.PublishData],
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

  lazy val millDiscover: mill.define.Discover = mill.define.Discover[this.type]
}
