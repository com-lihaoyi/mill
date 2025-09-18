package mill.javalib

import com.lihaoyi.unroll
import com.lumidion.sonatype.central.client.core.{PublishingType, SonatypeCredentials}
import mill.*
import javalib.*
import mill.javalib.internal
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
import mill.api.daemon.Logger
import mill.javalib.PublishModule.PublishData
import mill.javalib.internal.PublishModule.GpgArgs

trait SonatypeCentralPublishModule extends PublishModule, MavenWorkerSupport {

  @deprecated("Use `sonatypeCentralGpgArgsForKey` instead.", "Mill 1.0.1")
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

  // noinspection ScalaUnusedSymbol - used as a Mill task invokable from CLI
  def publishSonatypeCentral(
      username: String = defaultCredentials,
      password: String = defaultCredentials,
      @unroll sources: Boolean = true,
      @unroll docs: Boolean = true
  ): Task.Command[Unit] = Task.Command {
    val artifact = artifactMetadata()
    val credentials = getSonatypeCredentials(username, password)()
    val publishData = publishArtifactsPayload(sources = sources, docs = docs)()
    val publishingType = getPublishingTypeFromReleaseFlag(sonatypeCentralShouldRelease())

    val maybeKeyId = internal.PublishModule.pgpImportSecretIfProvidedOrThrow(Task.env)

    def makeGpgArgs() =
      sonatypeCentralGpgArgsForKey()(maybeKeyId.getOrElse(throw new IllegalArgumentException(
        s"Publishing to Sonatype Central requires a PGP key. Please set the " +
          s"'${internal.PublishModule.EnvVarPgpSecretBase64}' and '${internal.PublishModule.EnvVarPgpPassphrase}' " +
          s"(if needed) environment variables."
      )))

    SonatypeCentralPublishModule.publishAll(
      Seq(PublishData(artifact, publishData)),
      bundleName = None,
      credentials,
      publishingType,
      makeGpgArgs,
      awaitTimeout = sonatypeCentralAwaitTimeout(),
      connectTimeout = sonatypeCentralConnectTimeout(),
      readTimeout = sonatypeCentralReadTimeout(),
      sonatypeCentralSnapshotUri = sonatypeCentralSnapshotUri,
      taskDest = Task.dest,
      log = Task.log,
      env = Task.env,
      worker = mavenWorker()
    )
  }
}

/**
 * External module to publish artifacts to `central.sonatype.org`
 */
object SonatypeCentralPublishModule extends ExternalModule with DefaultTaskModule
    with MavenWorkerSupport {
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
      @unroll snapshotUri: String = PublishModule.sonatypeCentralSnapshotUri
  ): Command[Unit] = Task.Command {
    val artifacts = Task.sequence(publishArtifacts.value)()

    val finalBundleName = if (bundleName.isEmpty) None else Some(bundleName)
    val credentials = getSonatypeCredentials(username, password)()
    def makeGpgArgs() = internal.PublishModule.pgpImportSecretIfProvidedAndMakeGpgArgs(
      Task.env,
      GpgArgs.fromUserProvided(gpgArgs)
    )
    val publishingType = getPublishingTypeFromReleaseFlag(shouldRelease)

    publishAll(
      artifacts,
      finalBundleName,
      credentials,
      publishingType,
      makeGpgArgs,
      readTimeout = readTimeout,
      connectTimeout = connectTimeout,
      awaitTimeout = awaitTimeout,
      sonatypeCentralSnapshotUri = snapshotUri,
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
      publishingType: PublishingType,
      makeGpgArgs: () => GpgArgs,
      readTimeout: Int,
      connectTimeout: Int,
      awaitTimeout: Int,
      sonatypeCentralSnapshotUri: String,
      taskDest: os.Path,
      log: Logger,
      env: Map[String, String],
      worker: internal.MavenWorkerSupport.Api
  ): Unit = {
    val dryRun = env.get("MILL_TESTS_PUBLISH_DRY_RUN").contains("1")

    def publishSnapshot(publishData: PublishData): Unit = {
      val uri = sonatypeCentralSnapshotUri
      val artifacts = MavenWorkerSupport.RemoteM2Publisher.asM2ArtifactsFromPublishDatas(
        publishData.meta,
        publishData.payloadAsMap
      )

      log.info(
        s"Detected a 'SNAPSHOT' version for ${publishData.meta}, publishing to Sonatype Central Snapshots at '$uri'"
      )

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

    def publishReleases(artifacts: Seq[PublishData], gpgArgs: GpgArgs): Unit = {
      val publisher = new SonatypeCentralPublisher(
        credentials = credentials,
        gpgArgs = gpgArgs,
        connectTimeout = connectTimeout,
        readTimeout = readTimeout,
        log = log,
        workspace = BuildCtx.workspaceRoot,
        env = env,
        awaitTimeout = awaitTimeout
      )

      val artifactDatas = artifacts.map(_.withConcretePath)
      if (dryRun) {
        val publishTo = taskDest / "repository"
        log.info(
          s"Dry-run publishing all release artifacts to '$publishTo': ${pprint.apply(artifacts)}"
        )
        publisher.publishAllToLocal(publishTo, singleBundleName = bundleName, artifactDatas*)
        log.info(s"Dry-run publishing to '$publishTo' finished.")
      } else {
        log.info(
          s"Publishing all release artifacts to Sonatype Central (publishing type = $publishingType): ${
              pprint.apply(artifacts)
            }"
        )
        publisher.publishAll(publishingType, singleBundleName = bundleName, artifactDatas*)
        log.info(s"Published all release artifacts to Sonatype Central.")
      }
    }

    val (snapshots, releases) = publishArtifacts.partition(_.meta.isSnapshot)

    bundleName.filter(_ => snapshots.nonEmpty).foreach { bundleName =>
      throw new IllegalArgumentException(
        s"Publishing SNAPSHOT versions when bundle name ($bundleName) is specified is not supported.\n\n" +
          s"SNAPSHOT versions: ${pprint.apply(snapshots)}"
      )
    }

    if (releases.nonEmpty) {
      // If this fails do not publish anything.
      val gpgArgs = makeGpgArgs()
      publishReleases(releases, gpgArgs)
    }
    snapshots.foreach(publishSnapshot)
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
