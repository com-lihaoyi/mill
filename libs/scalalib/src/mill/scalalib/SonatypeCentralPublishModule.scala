package mill.scalalib

import com.lumidion.sonatype.central.client.core.{PublishingType, SonatypeCredentials}
import mill._
import scalalib._
import define.{ExternalModule, Task}
import mill.util.Tasks
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
import mill.define.BuildCtx

@experimental
trait SonatypeCentralPublishModule extends PublishModule {
  /**
   * @return (keyId => gpgArgs), where maybeKeyId is the PGP key that was imported and should be used for signing.
   */
  def sonatypeCentralGpgArgs: Task[String => Seq[String]] = Task.Anon { (keyId: String) =>
    PublishModule.makeGpgArgs(Task.env, maybeKeyId = Some(keyId), providedGpgArgs = Seq.empty)
  }

  def sonatypeCentralConnectTimeout: T[Int] = Task { defaultConnectTimeout }

  def sonatypeCentralReadTimeout: T[Int] = Task { defaultReadTimeout }

  def sonatypeCentralAwaitTimeout: T[Int] = Task { defaultAwaitTimeout }

  def sonatypeCentralShouldRelease: T[Boolean] = Task { true }

  def publishSonatypeCentral(
      username: String = defaultCredentials,
      password: String = defaultCredentials
  ): Task.Command[Unit] =
    Task.Command {
      val publishData = publishArtifacts()
      val fileMapping = publishData.withConcretePath._1
      val artifact = publishData.meta
      val finalCredentials = getSonatypeCredentials(username, password)()
      val maybeKeyId = PublishModule.pgpImportSecretIfProvidedOrThrow(Task.env)
      val keyId = maybeKeyId.getOrElse(throw new IllegalArgumentException(
        s"Publishing to Sonatype Central requires a PGP key. Please set the '${PublishModule.EnvVarPgpSecretBase64}' " +
          s"and '${PublishModule.EnvVarPgpPassphrase}' (if needed) environment variables."
      ))
      val gpgArgs = sonatypeCentralGpgArgs()(keyId)
      val publisher = new SonatypeCentralPublisher(
        credentials = finalCredentials,
        gpgArgs = gpgArgs,
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
      publishArtifacts: mill.util.Tasks[PublishModule.PublishData] =
        Tasks.resolveMainDefault("__:PublishModule.publishArtifacts"),
      username: String = defaultCredentials,
      password: String = defaultCredentials,
      shouldRelease: Boolean = defaultShouldRelease,
      gpgArgs: Seq[String] = Seq.empty,
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
    val gpgArgs0 = PublishModule.pgpImportSecretIfProvidedAndMakeGpgArgs(Task.env, gpgArgs)
    val publisher = new SonatypeCentralPublisher(
      credentials = finalCredentials,
      gpgArgs = gpgArgs0,
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

  lazy val millDiscover: mill.define.Discover = mill.define.Discover[this.type]
}
