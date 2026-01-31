package mill.javalib

import com.lihaoyi.unroll
import mill.T
import mill.given
import mill.api.daemon.Logger
import mill.api.{BuildCtx, DefaultTaskModule, ExternalModule, Result, Task}
import mill.javalib.PublishModule.PublishData
import mill.javalib.internal.PublishModule.GpgArgs
import mill.javalib.api.PgpKeyMaterial
import mill.javalib.publish.SonatypeHelpers.CREDENTIALS_ENV_VARIABLE_PREFIX
import mill.javalib.publish.{Artifact, PublishingType, SonatypeCredentials}
import mill.util.Tasks

trait SonatypeCentralPublishModule extends PublishModule, MavenWorkerSupport, PublishCredentialsModule {
  import SonatypeCentralPublishModule.*

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
    val credentials = getPublishCredentials(CREDENTIALS_ENV_VARIABLE_PREFIX, username, password)()
    val publishData = publishArtifactsPayload(sources = sources, docs = docs)()
    val publishingType = getPublishingTypeFromReleaseFlag(sonatypeCentralShouldRelease())

    val maybeKeyId = internal.PublishModule.pgpImportSecretIfProvidedOrThrow(Task.env, pgpWorker())

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
      worker = mavenWorker(),
      pgpWorker = pgpWorker()
    )
  }
}

/**
 * External module to publish artifacts to `central.sonatype.org`
 */
object SonatypeCentralPublishModule extends ExternalModule, DefaultTaskModule, MavenWorkerSupport,
      PgpWorkerSupport, PublishCredentialsModule, MavenPublish {
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
  ): Task.Command[Unit] = Task.Command {
    val artifacts = Task.sequence(publishArtifacts.value)()

    val finalBundleName = if (bundleName.isEmpty) None else Some(bundleName)
    val credentials = getPublishCredentials(CREDENTIALS_ENV_VARIABLE_PREFIX, username, password)()
    def makeGpgArgs() = internal.PublishModule.pgpImportSecretIfProvidedAndMakeGpgArgs(
      Task.env,
      GpgArgs.fromUserProvided(gpgArgs),
      pgpWorker()
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
      worker = mavenWorker(),
      pgpWorker = pgpWorker()
    )
  }

  private def publishAll(
      publishArtifacts: Seq[PublishData],
      bundleName: Option[String],
      credentials: (username: String, password: String),
      publishingType: PublishingType,
      makeGpgArgs: () => GpgArgs,
      readTimeout: Int,
      connectTimeout: Int,
      awaitTimeout: Int,
      sonatypeCentralSnapshotUri: String,
      taskDest: os.Path,
      log: Logger,
      env: Map[String, String],
      worker: internal.MavenWorkerSupport.Api,
      pgpWorker: mill.javalib.api.PgpWorkerApi
  ): Unit = {
    val dryRun = env.get("MILL_TESTS_PUBLISH_DRY_RUN").contains("1")

    def publishSnapshot(publishData: PublishData): Unit = {
      mavenPublishData(
        dryRun = dryRun,
        publishData = publishData,
        isSnapshot = true,
        credentials = credentials,
        releaseUri = sonatypeCentralSnapshotUri,
        snapshotUri = sonatypeCentralSnapshotUri,
        taskDest = taskDest,
        log = log,
        worker = worker
      )
    }

    def publishReleases(artifacts: Seq[PublishData], gpgArgs: GpgArgs): Unit = {
      val publisher = new SonatypeCentralPublisher2(
        credentials = SonatypeCredentials(credentials.username, credentials.password),
        gpgArgs = gpgArgs,
        pgpWorker = pgpWorker,
        connectTimeout = connectTimeout,
        readTimeout = readTimeout,
        log = log,
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
    if (shouldRelease) PublishingType.AUTOMATIC else PublishingType.USER_MANAGED
  }

  /**
   * Interactive task to create PGP keys for publishing to Sonatype Central.
   *
   * This task will:
   * 1. Generate a new PGP key pair (interactively prompting for name, email, and passphrase)
   * 2. Upload the public key to keyserver.ubuntu.com
   * 3. Verify the key was uploaded successfully
   * 4. Print the environment variables needed for publishing
   *
   * After running this task, copy the printed environment variables to your CI secrets
   * or shell configuration.
   *
   * See https://central.sonatype.org/publish/requirements/gpg/ for more details.
   */
  def initGpgKeys(): Task.Command[Unit] = Task.Command {
    val log = Task.log

    log.info("=== PGP Key Setup for Sonatype Central Publishing ===")
    log.info("")
    log.info("This will create a new PGP key pair for signing your artifacts.")
    log.info("You will be prompted to enter:")
    log.info("  - Your real name")
    log.info("  - Your email address")
    log.info("  - A passphrase to protect your key")
    log.info("")

    def prompt(label: String): String = {
      print(label)
      System.out.flush()
      scala.io.StdIn.readLine()
    }

    val name = prompt("Enter your name: ")
    val email = prompt("Enter your email: ")

    log.info("")
    log.info("Step 1: Generating PGP key pair...")
    val passphrase = {
      print("Enter passphrase (leave empty for no passphrase): ")
      System.out.flush()
      val console = System.console()
      if (console != null) new String(console.readPassword())
      else scala.io.StdIn.readLine()
    }
    if (passphrase == null || passphrase.isEmpty) {
      log.error("Warning: Empty passphrase provided")
    }
    val userId = s"$name <$email>"
    val generated: PgpKeyMaterial = pgpWorker().generateKeyPair(
      userId = userId,
      passphrase = Option(passphrase).filter(_.nonEmpty)
    )

    log.info("")
    log.info("PGP key generated successfully!")
    log.info("")

    val keyId = generated.keyIdHex
    log.info(s"Generated key ID: $keyId")
    log.info("")

    // Step 2: Upload public key to keyserver
    log.info("Step 2: Uploading public key to keyserver.ubuntu.com...")
    val uploadResult = requests.post(
      url = "https://keyserver.ubuntu.com/pks/add",
      data = Map("keytext" -> generated.publicKeyArmored)
    )
    if (!uploadResult.is2xx) {
      log.error(
        s"Warning: Failed to upload key to keyserver (status ${uploadResult.statusCode})."
      )
      log.error(
        "You may need to upload manually via https://keyserver.ubuntu.com/pks/add"
      )
    } else {
      log.info("Public key uploaded successfully!")
    }
    log.info("")

    // Step 3: Verify key was uploaded
    log.info("Step 3: Verifying key upload...")
    Thread.sleep(2000)
    val verifyResult = requests.get(
      url = "https://keyserver.ubuntu.com/pks/lookup",
      params = Map("op" -> "get", "search" -> s"0x$keyId")
    )
    if (!verifyResult.is2xx || !verifyResult.text().contains("BEGIN PGP PUBLIC KEY BLOCK")) {
      log.error("Warning: Could not verify key on keyserver.")
      log.error("This may be due to keyserver propagation delay. Try again later with:")
      log.error(s"  https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$keyId")
    } else {
      log.info("Key verified on keyserver!")
    }
    log.info("")

    // Base64 encode the key (without newlines for environment variable)
    val secretKeyBase64 =
      java.util.Base64.getEncoder.encodeToString(generated.secretKeyArmored.getBytes("UTF-8"))

    log.info("")
    log.info("=== Setup Complete! ===")
    log.info("")
    log.info("Add these environment variables to your CI configuration or shell:")
    log.info("")
    log.info("─" * 72)
    println(s"export MILL_PGP_SECRET_BASE64=$secretKeyBase64")
    println(s"export MILL_PGP_PASSPHRASE=$passphrase")
    log.info("─" * 72)
    log.info("")
    log.info("For GitHub Actions, add these as repository secrets:")
    log.info("  - MILL_PGP_SECRET_BASE64")
    log.info("  - MILL_PGP_PASSPHRASE")
    log.info("")
    log.info(s"Your key ID is: $keyId")
    log.info("")
    log.info("See https://central.sonatype.org/publish/requirements/gpg/ for more details.")
  }

  // TODO: make protected
  lazy val millDiscover: mill.api.Discover = mill.api.Discover[this.type]
}
