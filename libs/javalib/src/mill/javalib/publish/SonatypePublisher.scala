package mill.javalib.publish

import mill.api.Logger
import mill.javalib.internal.PublishModule.GpgArgs
import mill.javalib.publish.SonatypeHelpers.getArtifactMappings

import scala.annotation.targetName

/**
 * The publisher for the end-of-life OSSRH Sonatype publishing.
 *
 * You should migrate to [[mill.javalib.SonatypeCentralPublisher]] instead.
 *
 * @see https://central.sonatype.org/pages/ossrh-eol/
 */
@deprecated("Use `mill.javalib.SonatypeCentralPublisher` instead", "Mill 1.0.0")
class SonatypePublisher(
    uri: String,
    snapshotUri: String,
    credentials: String,
    signed: Boolean,
    gpgArgs: GpgArgs,
    readTimeout: Int,
    connectTimeout: Int,
    log: Logger,
    workspace: os.Path,
    env: Map[String, String],
    awaitTimeout: Int,
    stagingRelease: Boolean
) {
  // bincompat forwarder
  def this(
      uri: String,
      snapshotUri: String,
      credentials: String,
      signed: Boolean,
      gpgArgs: Seq[String],
      readTimeout: Int,
      connectTimeout: Int,
      log: Logger,
      workspace: os.Path,
      env: Map[String, String],
      awaitTimeout: Int,
      stagingRelease: Boolean
  ) = this(
    uri = uri,
    snapshotUri = snapshotUri,
    credentials = credentials,
    signed = signed,
    gpgArgs = GpgArgs.UserProvided(gpgArgs),
    readTimeout = readTimeout,
    connectTimeout = connectTimeout,
    log = log,
    workspace = workspace,
    env = env,
    awaitTimeout = awaitTimeout,
    stagingRelease = stagingRelease
  )

  private val api = new SonatypeHttpApi(
    uri,
    credentials,
    readTimeout = readTimeout,
    connectTimeout = connectTimeout
  )

  // binary compatibility forwarder
  def publish(fileMapping: Seq[(os.Path, String)], artifact: Artifact, release: Boolean): Unit =
    publishAll(
      release,
      fileMapping.iterator.map { case (path, name) => os.SubPath(name) -> path }.toMap -> artifact
    )

  def publish(fileMapping: Map[os.SubPath, os.Path], artifact: Artifact, release: Boolean): Unit =
    publishAll(release, fileMapping -> artifact)

  // binary compatibility forwarder
  def publishAll(release: Boolean, artifacts: (Seq[(os.Path, String)], Artifact)*): Unit = {
    val mappedArtifacts = artifacts.iterator.map { case (fileMapping, artifact) =>
      val mapping = fileMapping.iterator.map { case (path, name) => os.SubPath(name) -> path }.toMap
      mapping -> artifact
    }.toArray
    publishAll(release, mappedArtifacts*)
  }

  @targetName("publishAllByMap")
  def publishAll(release: Boolean, artifacts: (Map[os.SubPath, os.Path], Artifact)*): Unit = {
    val mappings = getArtifactMappings(signed, gpgArgs, workspace, env, artifacts)

    val (snapshots, releases) = mappings.partition(_.artifact.isSnapshot)
    if (snapshots.nonEmpty) {
      publishSnapshot(
        snapshots.iterator.flatMap(_.contents).toMap,
        snapshots.map(_.artifact)
      )
    }
    val releaseGroups = releases.groupBy(_.artifact.group)
    for ((group, groupReleases) <- releaseGroups) {
      val groupArtifacts = groupReleases.map(_.artifact)
      val groupContents = groupReleases.iterator.flatMap(_.contents).toMap
      if (stagingRelease)
        publishRelease(release, groupContents, group, groupArtifacts, awaitTimeout)
      else publishReleaseNonstaging(groupContents, groupArtifacts)
    }
  }

  private def publishSnapshot(
      payloads: Map[os.SubPath, Array[Byte]],
      artifacts: Seq[Artifact]
  ): Unit = {
    publishToUri(payloads, artifacts, snapshotUri)
  }

  private def publishToUri(
      payloads: Map[os.SubPath, Array[Byte]],
      artifacts: Seq[Artifact],
      uri: String
  ): Unit = {
    val publishResults = payloads.iterator.map {
      case (fileName, data) =>
        log.info(s"Uploading $fileName")
        api.upload(s"$uri/$fileName", data)
    }.toVector
    reportPublishResults(publishResults, artifacts)
  }

  private def publishReleaseNonstaging(
      payloads: Map[os.SubPath, Array[Byte]],
      artifacts: Seq[Artifact]
  ): Unit = {
    publishToUri(payloads, artifacts, uri)
  }

  private def publishRelease(
      release: Boolean,
      payloads: Map[os.SubPath, Array[Byte]],
      stagingProfile: String,
      artifacts: Seq[Artifact],
      awaitTimeout: Int
  ): Unit = {
    val profileUri = api.getStagingProfileUri(stagingProfile)
    val stagingRepoId =
      api.createStagingRepo(profileUri, stagingProfile)
    val baseUri = s"$uri/staging/deployByRepositoryId/$stagingRepoId"

    publishToUri(payloads, artifacts, baseUri)

    if (release) {
      log.info("Closing staging repository")
      api.closeStagingRepo(profileUri, stagingRepoId)

      log.info("Waiting for staging repository to close")
      awaitRepoStatus("closed", stagingRepoId, awaitTimeout)

      log.info("Promoting staging repository")
      api.promoteStagingRepo(profileUri, stagingRepoId)

      log.info("Waiting for staging repository to release")
      awaitRepoStatus("released", stagingRepoId, awaitTimeout)

      log.info("Dropping staging repository")
      api.dropStagingRepo(profileUri, stagingRepoId)

      log.info(s"Published ${artifacts.map(_.id).mkString(", ")} successfully")
    }
  }

  private def reportPublishResults(
      publishResults: Seq[requests.Response],
      artifacts: Seq[Artifact]
  ): Unit = {
    if (publishResults.forall(_.is2xx)) {
      log.info(s"Published ${artifacts.map(_.id).mkString(", ")} to Sonatype")
    } else {
      val errors = publishResults.filterNot(_.is2xx).map { response =>
        s"Code: ${response.statusCode}, message: ${response.text()}"
      }
      throw new RuntimeException(
        s"Failed to publish ${artifacts.map(_.id).mkString(", ")} to Sonatype. Errors: \n${errors.mkString("\n")}"
      )
    }
  }

  private def awaitRepoStatus(status: String, stagingRepoId: String, awaitTimeout: Int): Unit = {
    def isRightStatus =
      api.getStagingRepoState(stagingRepoId).equalsIgnoreCase(status)

    var attemptsLeft = awaitTimeout / 3000

    while (attemptsLeft > 0 && !isRightStatus) {
      Thread.sleep(3000)
      attemptsLeft -= 1
      if (attemptsLeft == 0) {
        throw new RuntimeException(
          s"Couldn't wait for staging repository to be ${status}. Failing"
        )
      }
    }
  }
}
