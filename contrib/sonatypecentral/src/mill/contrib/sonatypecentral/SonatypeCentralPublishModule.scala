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
import mill.scalalib.PublishModule.{defaultGpgArgs, getFinalGpgArgs}
import mill.scalalib.publish.Artifact
import mill.scalalib.publish.SonatypeHelpers.{
  PASSWORD_ENV_VARIABLE_NAME,
  USERNAME_ENV_VARIABLE_NAME
}

trait SonatypeCentralPublishModule extends PublishModule {
  def sonatypeCentralGpgArgs: T[String] = T { defaultGpgArgs.mkString(",") }

  def sonatypeCentralConnectTimeout: T[Int] = T { defaultConnectTimeout }

  def sonatypeCentralReadTimeout: T[Int] = T { defaultReadTimeout }

  def sonatypeCentralAwaitTimeout: T[Int] = T { defaultAwaitTimeout }

  def sonatypeCentralShouldRelease: T[Boolean] = T { true }

  def publishSonatypeCentral(
      username: String = defaultCredentials,
      password: String = defaultCredentials
  ): define.Command[Unit] =
    T.command {
      val publishData = publishArtifacts()
      val fileMapping = publishData.withConcretePath._1
      val artifact = publishData.meta
      val finalCredentials = getSonatypeCredentials(username, password)()

      val publisher = new SonatypeCentralPublisher(
        credentials = finalCredentials,
        gpgArgs = getFinalGpgArgs(sonatypeCentralGpgArgs()),
        connectTimeout = sonatypeCentralConnectTimeout(),
        readTimeout = sonatypeCentralReadTimeout(),
        log = T.log,
        workspace = T.workspace,
        env = T.env,
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
      publishArtifacts: mill.main.Tasks[PublishModule.PublishData],
      username: String = defaultCredentials,
      password: String = defaultCredentials,
      shouldRelease: Boolean = defaultShouldRelease,
      gpgArgs: String = defaultGpgArgs.mkString(","),
      readTimeout: Int = defaultReadTimeout,
      connectTimeout: Int = defaultConnectTimeout,
      awaitTimeout: Int = defaultAwaitTimeout,
      bundleName: String = ""
  ): Command[Unit] = T.command {

    val artifacts: Seq[(Seq[(os.Path, String)], Artifact)] =
      T.sequence(publishArtifacts.value)().map {
        case data @ PublishModule.PublishData(_, _) => data.withConcretePath
      }

    val finalBundleName = if (bundleName.isEmpty) None else Some(bundleName)
    val finalCredentials = getSonatypeCredentials(username, password)()

    val publisher = new SonatypeCentralPublisher(
      credentials = finalCredentials,
      gpgArgs = getFinalGpgArgs(gpgArgs),
      connectTimeout = connectTimeout,
      readTimeout = readTimeout,
      log = T.log,
      workspace = T.workspace,
      env = T.env,
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
  ): Task[String] = T.task {
    if (credentialParameterValue.nonEmpty) {
      Result.Success(credentialParameterValue)
    } else {
      (for {
        credential <- T.env.get(envVariableName)
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
  ): Task[SonatypeCredentials] = T.task {
    val username =
      getSonatypeCredential(usernameParameterValue, "username", USERNAME_ENV_VARIABLE_NAME)()
    val password =
      getSonatypeCredential(passwordParameterValue, "password", PASSWORD_ENV_VARIABLE_NAME)()
    Result.Success(SonatypeCredentials(username, password))
  }

  lazy val millDiscover: mill.define.Discover[this.type] = mill.define.Discover[this.type]
}
