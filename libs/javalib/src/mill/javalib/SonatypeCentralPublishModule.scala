package mill.javalib

import com.lumidion.sonatype.central.client.core.{PublishingType, SonatypeCredentials}
import mill.*
import javalib.*
import mill.api.{ExternalModule, Task}
import mill.util.Tasks
import mill.api.DefaultTaskModule
import mill.api.Result
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
import mill.javalib.internal.PublishModule.GpgArgs

trait SonatypeCentralPublishModule extends PublishModule with MavenWorkerSupport {
  @deprecated("Use `sonatypeCentralGpgArgsForKey` instead.", "1.0.1")
  def sonatypeCentralGpgArgs: T[String] =
    Task { SonatypeCentralPublishModule.sonatypeCentralGpgArgsSentinelValue }

  /**
   * @return (keyId => gpgArgs), where maybeKeyId is the PGP key that was imported and should be used for signing.
   */
  def sonatypeCentralGpgArgsForKey: Task[String => GpgArgs] = Task.Anon { (keyId: String) =>
    val sentinel = SonatypeCentralPublishModule.sonatypeCentralGpgArgsSentinelValue
    // noinspection ScalaDeprecation
    sonatypeCentralGpgArgs() match {
      case `sentinel` =>
        internal.PublishModule.makeGpgArgs(
          Task.env,
          maybeKeyId = Some(keyId),
          providedGpgArgs = GpgArgs.UserProvided(Seq.empty)
        )
      case other =>
        GpgArgs.fromUserProvided(other)
    }
  }

  def sonatypeCentralConnectTimeout: T[Int] = Task { defaultConnectTimeout }

  def sonatypeCentralReadTimeout: T[Int] = Task { defaultReadTimeout }

  def sonatypeCentralAwaitTimeout: T[Int] = Task { defaultAwaitTimeout }

  def sonatypeCentralShouldRelease: T[Boolean] = Task { true }

  def publishSonatypeCentral(
      username: String = defaultCredentials,
      password: String = defaultCredentials,
      force: Boolean = false
  ): Task.Command[Unit] = Task.Command {
    val artifact = artifactMetadata()
    val finalCredentials = getSonatypeCredentials(username, password, force)()
    val dryRun = Task.env.get("MILL_TESTS_PUBLISH_DRY_RUN").contains("1")

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

      if (dryRun) {
        val publishTo = Task.dest / "repository"
        val result = worker.publishToLocal(
          publishTo = publishTo,
          workspace = Task.dest / "maven",
          artifacts
        )
        Task.log.info(s"Dry-run publishing to '$publishTo' finished with result: $result")
      } else {
        val result = worker.publishToRemote(
          uri = uri,
          workspace = Task.dest / "maven",
          username = finalCredentials.username,
          password = finalCredentials.password,
          artifacts
        )
        Task.log.info(s"Publishing to '$uri' finished with result: $result")
      }
    }

    def publishRelease(): Unit = {
      val publishData = publishArtifacts()
      val fileMapping = publishData.withConcretePath._1

      val maybeKeyId = internal.PublishModule.pgpImportSecretIfProvidedOrThrow(Task.env)
      val keyId = maybeKeyId.getOrElse(throw new IllegalArgumentException(
        s"Publishing to Sonatype Central requires a PGP key. Please set the " +
          s"'${internal.PublishModule.EnvVarPgpSecretBase64}' and '${internal.PublishModule.EnvVarPgpPassphrase}' " +
          s"(if needed) environment variables."
      ))

      val gpgArgs = sonatypeCentralGpgArgsForKey()(keyId)
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

      if (dryRun) {
        val publishTo = Task.dest / "repository"
        publisher.publishAllToLocal(
          publishTo,
          singleBundleName = None,
          (fileMapping, artifact)
        )
        Task.log.info(s"Dry-run publishing to '$publishTo' finished.")
      } else {
        publisher.publish(
          fileMapping,
          artifact,
          getPublishingTypeFromReleaseFlag(sonatypeCentralShouldRelease())
        )
        Task.log.info("Publishing finished.")
      }
    }

    // The snapshot publishing does not use the same API as release publishing.
    if (artifact.version.endsWith("SNAPSHOT")) publishSnapshot()
    else publishRelease()
  }

  // bin-compat shim
  def publishSonatypeCentral(
      username: String,
      password: String
  ): Task.Command[Unit] =
    publishSonatypeCentral(
      username,
      password,
      force = false
    )
}

/**
 * External module to publish artifacts to `central.sonatype.org`
 */
object SonatypeCentralPublishModule extends ExternalModule with DefaultTaskModule {
  private final val sonatypeCentralGpgArgsSentinelValue = "<user did not override this method>"

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
      bundleName: String = "",
      force: Boolean = false
  ): Command[Unit] = Task.Command {

    val artifacts =
      Task.sequence(publishArtifacts.value)().map(_.withConcretePath)

    val finalBundleName = if (bundleName.isEmpty) None else Some(bundleName)
    val finalCredentials = getSonatypeCredentials(username, password, force)()
    val gpgArgs0 = internal.PublishModule.pgpImportSecretIfProvidedAndMakeGpgArgs(
      Task.env,
      GpgArgs.fromUserProvided(gpgArgs)
    )
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

  // bin-compat shim
  def publishAll(
      publishArtifacts: mill.util.Tasks[PublishModule.PublishData],
      username: String,
      password: String,
      shouldRelease: Boolean,
      gpgArgs: String,
      readTimeout: Int,
      connectTimeout: Int,
      awaitTimeout: Int,
      bundleName: String
  ): Command[Unit] =
    publishAll(
      publishArtifacts,
      username,
      password,
      shouldRelease,
      gpgArgs,
      readTimeout,
      connectTimeout,
      awaitTimeout,
      bundleName,
      force = false
    )

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
      passwordParameterValue: String,
      force: Boolean
  ): Task[SonatypeCredentials] = Task.Anon {
    val isCI = Task.env.get("CI").nonEmpty
    if (!force && isCI && (usernameParameterValue.nonEmpty || passwordParameterValue.nonEmpty))
      sys.error(
        "--username and --password options forbidden on CI. " +
          "Their use might leak secrets. " +
          s"Pass those values via environment variables instead ($USERNAME_ENV_VARIABLE_NAME and $PASSWORD_ENV_VARIABLE_NAME), or pass --force alongside them. " +
          "You might want to check the output of this job for a leak of those secrets or parts of them."
      )
    val username =
      getSonatypeCredential(usernameParameterValue, "username", USERNAME_ENV_VARIABLE_NAME)()
    val password =
      getSonatypeCredential(passwordParameterValue, "password", PASSWORD_ENV_VARIABLE_NAME)()
    Result.Success(SonatypeCredentials(username, password))
  }

  lazy val millDiscover: mill.api.Discover = mill.api.Discover[this.type]
}
