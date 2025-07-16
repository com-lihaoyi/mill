package mill.javalib

import com.lihaoyi.unroll
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
import mill.javalib.PublishModule.PublishData
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

  //noinspection ScalaUnusedSymbol - used as a Mill task invocable from CLI
  def publishSonatypeCentral(
      username: String = defaultCredentials,
      password: String = defaultCredentials,
      @unroll sources: Boolean = true,
      @unroll docs: Boolean = true
  ): Task.Command[Unit] = Task.Command {
    val artifact = artifactMetadata()
    val finalCredentials = getSonatypeCredentials(username, password)()

    def publishSnapshot(): Unit = {
      val uri = sonatypeCentralSnapshotUri
      val artifacts = MavenWorkerSupport.RemoteM2Publisher.asM2Artifacts(
        pom().path,
        artifact,
        defaultPublishInfos(sources = sources, docs = docs)()
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
      val publishData = publishArtifactsPayload(sources = sources, docs = docs)()
      val fileMapping = PublishData.withConcretePath(publishData)

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
      bundleName: String = ""
  ): Command[Unit] = Task.Command {
    val artifacts = Task.sequence(publishArtifacts.value)().map(_.withConcretePath)
    val (snapshotArtifacts, releaseArtifacts) = artifacts.partition(_._2.isSnapshot)
    val log = Task.log

    if (snapshotArtifacts.nonEmpty) {
      val commonMessage =
        "\n" +
          "Please extend `SonatypeCentralPublishModule` and use its `publishSonatypeCentral` task to publish " +
          "snapshots.\n" +
          "\n" +
          s"Found the following SNAPSHOT artifacts: ${pprint(snapshotArtifacts)}"

      if (releaseArtifacts.isEmpty) {
        // We can not do anything here because we need more metadata about the published files than `artifacts` provide.
        throw new IllegalArgumentException(
          "All artifacts to publish are SNAPSHOTs, but publishing SNAPSHOTs to Sonatype Central is not " +
            s"supported with this task.\n" +
            commonMessage
        )
      } else {
        log.warn(
          "Some of the artifacts to publish are SNAPSHOTs, but publishing SNAPSHOTs to Sonatype Central is not " +
            s"supported with this task. SNAPSHOT artifacts will be skipped.\n" +
            commonMessage
        )
      }
    }

    val finalBundleName = if (bundleName.isEmpty) None else Some(bundleName)
    val credentials = getSonatypeCredentials(username, password)()
    val finalGpgArgs = internal.PublishModule.pgpImportSecretIfProvidedAndMakeGpgArgs(
      Task.env,
      GpgArgs.fromUserProvided(gpgArgs)
    )
    val publishingType = getPublishingTypeFromReleaseFlag(shouldRelease)

    val publisher = new SonatypeCentralPublisher(
      credentials = credentials,
      gpgArgs = finalGpgArgs,
      connectTimeout = connectTimeout,
      readTimeout = readTimeout,
      log = log,
      workspace = BuildCtx.workspaceRoot,
      env = Task.env,
      awaitTimeout = awaitTimeout
    )
    log.info(
      s"Publishing all release artifacts to Sonatype Central (publishing type = $publishingType): ${
          pprint.apply(releaseArtifacts)
        }"
    )
    publisher.publishAll(publishingType, singleBundleName = finalBundleName, releaseArtifacts*)
    log.info(s"Published all release artifacts to Sonatype Central.")
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
