package mill.contrib.sonatypecentral

import com.lumidion.sonatype.central.client.core.{PublishingType, SonatypeCredentials}
import mill._
import scalalib._
import define.{ExternalModule, Task}
import mill.api.Result
import mill.contrib.sonatypecentral.SonatypeCentralPublishModule.{defaultAwaitTimeout, defaultConnectTimeout, defaultCredentials, defaultReadTimeout, getPublishingTypeFromReleaseFlag, getSonatypeCredentials}
import mill.scalalib.PublishModule.{defaultStringGpgArgs, getFinalGpgArgs}
import mill.scalalib.publish.Artifact
import mill.scalalib.publish.SonatypeHelpers.{PASSWORD_ENV_VARIABLE_NAME, USERNAME_ENV_VARIABLE_NAME}

trait SonatypeCentralPublishModule extends PublishModule {
  def gpgArgs: T[String] = T { defaultStringGpgArgs }

  def connectTimeout: T[Int] = T { defaultConnectTimeout }

  def readTimeout: T[Int] = T { defaultReadTimeout }

  def awaitTimeout: T[Int] = T { defaultAwaitTimeout }

  def shouldRelease: T[Boolean] = T { true }

  def publishSonatypeCentral(
      username: String = defaultCredentials,
      password: String = defaultCredentials
  ): define.Command[Unit] =
    T.command {
      val publishData = publishArtifacts()
      val fileMapping = publishData.toDataWithConcretePath._1
      val artifact = publishData.meta
      val finalCredentials = getSonatypeCredentials(username, password)()

      val publisher = new SonatypeCentralPublisher(
        credentials = finalCredentials,
        gpgArgs = getFinalGpgArgs(gpgArgs()),
        connectTimeout = connectTimeout(),
        readTimeout = readTimeout(),
        log = T.log,
        workspace = T.workspace,
        env = T.env,
        awaitTimeout = awaitTimeout()
      )
      publisher.publish(fileMapping, artifact, getPublishingTypeFromReleaseFlag(shouldRelease()))
    }
}

object SonatypeCentralPublishModule extends ExternalModule {

  private val defaultCredentials = ""
  private val defaultReadTimeout = 60000
  private val defaultConnectTimeout = 5000
  private val defaultAwaitTimeout = 120 * 1000
  private val defaultShouldRelease = true

  def publishAll(
      publishArtifacts: mill.main.Tasks[PublishModule.PublishData],
      username: String = defaultCredentials,
      password: String = defaultCredentials,
      shouldRelease: Boolean = defaultShouldRelease,
      gpgArgs: String = defaultStringGpgArgs,
      readTimeout: Int = defaultReadTimeout,
      connectTimeout: Int = defaultConnectTimeout,
      awaitTimeout: Int = defaultAwaitTimeout,
      bundleName: String = ""
  ): Command[Unit] = T.command {

    val artifacts: Seq[(Seq[(os.Path, String)], Artifact)] =
      T.sequence(publishArtifacts.value)().map {
        case data @ PublishModule.PublishData(_, _) => data.toDataWithConcretePath
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
    val username = getSonatypeCredential(usernameParameterValue, "username", USERNAME_ENV_VARIABLE_NAME)()
    val password = getSonatypeCredential(passwordParameterValue, "password", PASSWORD_ENV_VARIABLE_NAME)()
    Result.Success(SonatypeCredentials(username, password))
  }

  lazy val millDiscover: mill.define.Discover[this.type] = mill.define.Discover[this.type]
}
