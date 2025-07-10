package mill.javalib

import com.lumidion.sonatype.central.client.core.{
  DeploymentName,
  PublishingType,
  SonatypeCredentials
}
import com.lumidion.sonatype.central.client.requests.SyncSonatypeClient
import mill.api.{Logger, Result}
import mill.javalib.publish.Artifact
import mill.javalib.publish.SonatypeHelpers.getArtifactMappings

import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * Publishing logic for the standard Sonatype Central repository `central.sonatype.org`
 */
class SonatypeCentralPublisher(
    credentials: SonatypeCredentials,
    gpgArgs: PublishModule.GpgArgs,
    readTimeout: Int,
    connectTimeout: Int,
    log: Logger,
    workspace: os.Path,
    env: Map[String, String],
    awaitTimeout: Int
) {
  private val sonatypeCentralClient =
    new SyncSonatypeClient(credentials, readTimeout = readTimeout, connectTimeout = connectTimeout)

  def publish(
      fileMapping: Seq[(os.Path, String)],
      artifact: Artifact,
      publishingType: PublishingType
  ): Unit = {
    publishAll(publishingType, None, fileMapping -> artifact)
  }

  def publishAll(
      publishingType: PublishingType,
      singleBundleName: Option[String],
      artifacts: (Seq[(os.Path, String)], Artifact)*
  ): Unit = {
    val releases = getArtifactMappings(isSigned = true, gpgArgs, workspace, env, artifacts)
    log.info(s"mappings ${pprint.apply(releases.map { case (a, kvs) => (a, kvs.map(_._1)) })}")

    val releaseGroups = releases.groupBy(_._1.group)
    val wd = os.pwd / "out/publish-central"
    os.makeDir.all(wd)

    singleBundleName.fold {
      for ((_, groupReleases) <- releaseGroups) {
        groupReleases.foreach { case (artifact, data) =>
          val fileNameWithoutExtension = s"${artifact.group}-${artifact.id}-${artifact.version}"
          val zipFile = streamToFile(fileNameWithoutExtension, wd) { outputStream =>
            log.info(s"bundle $fileNameWithoutExtension with ${pprint.apply(data.map(_._1))}")
            zipFilesToJar(data, outputStream)
          }

          val deploymentName = DeploymentName.fromArtifact(
            artifact.group,
            artifact.id,
            artifact.version
          )
          publishFile(zipFile, deploymentName, publishingType)
        }
      }

    } { singleBundleName =>
      val zipFile = streamToFile(singleBundleName, wd) { outputStream =>
        for ((_, groupReleases) <- releaseGroups) {
          groupReleases.foreach { case (_, data) =>
            zipFilesToJar(data, outputStream)
          }
        }
      }

      val deploymentName = DeploymentName(singleBundleName)

      publishFile(zipFile, deploymentName, publishingType)
    }
  }

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
        sonatypeCentralClient.uploadBundleFromFile(
          zipFile,
          deploymentName,
          Some(publishingType),
          timeout = awaitTimeout
        )
      }
    } catch {
      case ex: Throwable => {
        throw new RuntimeException(
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
    val jarOutputStream = new JarOutputStream(fileOutputStream)
    try {
      func(jarOutputStream)
    } finally {
      jarOutputStream.close()
    }
    zipFile.toIO
  }

  private def zipFilesToJar(
      files: Seq[(String, Array[Byte])],
      jarOutputStream: JarOutputStream
  ): Unit = {
    files.foreach { case (filename, fileAsBytes) =>
      val zipEntry = new ZipEntry(filename)
      jarOutputStream.putNextEntry(zipEntry)
      jarOutputStream.write(fileAsBytes)
      jarOutputStream.closeEntry()
    }
  }
}
