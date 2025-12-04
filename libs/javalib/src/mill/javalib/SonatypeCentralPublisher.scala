package mill.javalib

import com.lumidion.sonatype.central.client.core.{
  DeploymentName,
  PublishingType as STPubType,
  SonatypeCredentials as STCreds
}
import com.lumidion.sonatype.central.client.requests.SyncSonatypeClient
import mill.api.Logger
import mill.javalib.internal.PublishModule.GpgArgs
import mill.javalib.internal.PublishModule.GpgArgs.UserProvided
import mill.javalib.publish.SonatypeHelpers.getArtifactMappings
import mill.javalib.publish.{Artifact, PublishingType, SonatypeCredentials}

import java.io.File
import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import scala.annotation.targetName

/**
 * Publishing logic for the standard Sonatype Central repository `central.sonatype.org`
 */
class SonatypeCentralPublisher(
    credentials: SonatypeCredentials,
    gpgArgs: GpgArgs,
    readTimeout: Int,
    connectTimeout: Int,
    log: Logger,
    workspace: os.Path,
    env: Map[String, String],
    awaitTimeout: Int
) {
  // bincompat forwarder
  def this(
      credentials: SonatypeCredentials,
      gpgArgs: Seq[String],
      readTimeout: Int,
      connectTimeout: Int,
      log: Logger,
      workspace: os.Path,
      env: Map[String, String],
      awaitTimeout: Int
  ) = this(
    credentials = credentials,
    gpgArgs = UserProvided(gpgArgs),
    readTimeout = readTimeout,
    connectTimeout = connectTimeout,
    log = log,
    workspace = workspace,
    env = env,
    awaitTimeout = awaitTimeout
  )

  private val sonatypeCentralClient =
    SyncSonatypeClient(
      credentials = STCreds(credentials.username, credentials.password),
      readTimeout = readTimeout,
      connectTimeout = connectTimeout
    )

  // binary compatibility forwarder
  @deprecated("Use `publish` where `fileMapping: Map[os.SubPath, os.Path]` instead.", "Mill 1.0.1")
  def publish(
      fileMapping: Seq[(os.Path, String)],
      artifact: Artifact,
      publishingType: PublishingType
  ): Unit =
    publish(
      fileMapping.iterator.map { case (path, name) => os.SubPath(name) -> path }.toMap,
      artifact,
      publishingType
    )

  def publish(
      fileMapping: Map[os.SubPath, os.Path],
      artifact: Artifact,
      publishingType: PublishingType
  ): Unit =
    publishAll(publishingType, singleBundleName = None, fileMapping -> artifact)

  def publishAll(
      publishingType: PublishingType,
      singleBundleName: Option[String],
      artifacts: (Seq[(os.Path, String)], Artifact)*
  ): Unit = {
    val mappedArtifacts = artifacts.iterator.map { case (fileMapping, artifact) =>
      val mapping = fileMapping.iterator.map { case (path, name) => os.SubPath(name) -> path }.toMap
      mapping -> artifact
    }.toArray
    publishAll(publishingType, singleBundleName, mappedArtifacts*)
  }

  @targetName("publishAllByMap")
  def publishAll(
      publishingType: PublishingType,
      singleBundleName: Option[String],
      artifacts: (Map[os.SubPath, os.Path], Artifact)*
  ): Unit = {
    val prepared = prepareToPublishAll(singleBundleName, artifacts*)
    log.info(prepared.mappingsString)

    prepared.deployments.foreach { case (zipFile, deploymentName) =>
      publishFile(zipFile, deploymentName, publishingType)
    }
  }

  private[mill] def publishAllToLocal(
      publishTo: os.Path,
      singleBundleName: Option[String],
      artifacts: (Map[os.SubPath, os.Path], Artifact)*
  ): Unit = {
    val prepared = prepareToPublishAll(singleBundleName, artifacts*)
    log.info(prepared.mappingsString)

    prepared.deployments.foreach { case (zipFile, deploymentName) =>
      val target = publishTo / deploymentName.unapply
      log.info(s"Unzipping $zipFile to $target")
      os.makeDir.all(target)
      os.unzip(os.Path(zipFile.toPath), target)
    }
  }

  private case class PreparedArtifacts(
      mappings: Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])],
      deployments: Vector[(zipFile: File, deploymentName: DeploymentName)]
  ) {
    def mappingsString: String = s"mappings ${pprint(
        mappings.map { case (a, fileSetContents) =>
          (a, fileSetContents.keys.toVector.sorted.map(_.toString))
        }
      )}"
  }

  /** Prepare artifacts for publishing. */
  private def prepareToPublishAll(
      singleBundleName: Option[String],
      artifacts: (Map[os.SubPath, os.Path], Artifact)*
  ): PreparedArtifacts = {
    val releases = getArtifactMappings(isSigned = true, gpgArgs, workspace, env, artifacts)

    val releaseGroups = releases.groupBy(_.artifact.group)
    val wd = os.pwd / "out/publish-central"
    os.makeDir.all(wd)

    singleBundleName match {
      case None =>
        val deployments = releaseGroups.valuesIterator.flatMap { groupReleases =>
          groupReleases.map { case (artifact, data) =>
            val fileNameWithoutExtension = s"${artifact.group}-${artifact.id}-${artifact.version}"
            val zipFile = streamToFile(fileNameWithoutExtension, wd) { outputStream =>
              log.info(
                s"bundle $fileNameWithoutExtension with ${pprint.apply(data.keys.toVector.sorted.map(_.toString))}"
              )
              zipFilesToJar(data, outputStream)
            }

            val deploymentName = DeploymentName.fromArtifact(
              artifact.group,
              artifact.id,
              artifact.version
            )
            (zipFile, deploymentName)
          }
        }.toVector

        PreparedArtifacts(releases, deployments)

      case Some(singleBundleName) =>
        val zipFile = streamToFile(singleBundleName, wd) { outputStream =>
          for ((_, groupReleases) <- releaseGroups) {
            groupReleases.foreach { case (_, data) =>
              zipFilesToJar(data, outputStream)
            }
          }
        }

        val deploymentName = DeploymentName(singleBundleName)
        PreparedArtifacts(releases, Vector((zipFile, deploymentName)))
    }
  }

  /** Publishes a zip file to Sonatype Central. */
  private def publishFile(
      zipFile: java.io.File,
      deploymentName: DeploymentName,
      publishingType: PublishingType
  ): Unit = {
    try {
      mill.util.Retry(
        count = 5,
        backoffMillis = 1000,
        filter = (_, ex) => ex.getMessage.contains("Read end dead")
      ) {
        val stPubType = publishingType match {
          case PublishingType.AUTOMATIC => STPubType.AUTOMATIC
          case PublishingType.USER_MANAGED => STPubType.USER_MANAGED
        }

        sonatypeCentralClient.uploadBundleFromFile(
          localBundlePath = zipFile,
          deploymentName = deploymentName,
          publishingType = Some(stPubType),
          timeout = awaitTimeout
        )
      }
    } catch {
      case ex: Throwable => {
        throw RuntimeException(
          s"Failed to publish ${deploymentName.unapply} to Sonatype Central",
          ex
        )
      }
    }

    log.info(s"Successfully published ${deploymentName.unapply} to Sonatype Central")
  }

  private def streamToFile(
      fileNameWithoutExtension: String,
      wd: os.Path
  )(func: JarOutputStream => Unit): java.io.File = {
    val zipFile =
      (wd / s"$fileNameWithoutExtension.zip")
    val fileOutputStream = Files.newOutputStream(zipFile.toNIO)
    val jarOutputStream = JarOutputStream(fileOutputStream)
    try {
      func(jarOutputStream)
    } finally {
      jarOutputStream.close()
    }
    zipFile.toIO
  }

  private def zipFilesToJar(
      files: Map[os.SubPath, Array[Byte]],
      jarOutputStream: JarOutputStream
  ): Unit = {
    files.foreach { case (filename, fileAsBytes) =>
      val zipEntry = ZipEntry(filename.toString)
      jarOutputStream.putNextEntry(zipEntry)
      jarOutputStream.write(fileAsBytes)
      jarOutputStream.closeEntry()
    }
  }
}
