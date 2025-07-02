package mill.javalib

import com.lumidion.sonatype.central.client.core.{
  DeploymentName,
  PublishingType,
  SonatypeCredentials
}
import com.lumidion.sonatype.central.client.requests.SyncSonatypeClient
import mill.api.{Logger, Result}
import mill.scalalib.publish.Artifact
import mill.scalalib.publish.SonatypeHelpers.getArtifactMappings
import mill.util.FileSetContents

import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class SonatypeCentralPublisher(
    credentials: SonatypeCredentials,
    gpgArgs: Seq[String],
    readTimeout: Int,
    connectTimeout: Int,
    log: Logger,
    workspace: os.Path,
    env: Map[String, String],
    awaitTimeout: Int
) {
  private val sonatypeCentralClient =
    new SyncSonatypeClient(credentials, readTimeout = readTimeout, connectTimeout = connectTimeout)/* {
      override protected val clientBaseUrl         = s"https://central.sonatype.com/repository/maven-snapshots/"
      override protected val clientUploadBundleUrl = s"$clientBaseUrl/upload"
      override protected val clientCheckStatusUrl  = s"$clientBaseUrl/status"
    }*/

  def publish(
      fileMapping: FileSetContents.Path,
      artifact: Artifact,
      publishingType: PublishingType
  ): Unit = {
    publishAll(publishingType, None, fileMapping -> artifact)
  }

  def publishAll(
      publishingType: PublishingType,
      singleBundleName: Option[String],
      artifacts: (FileSetContents.Path, Artifact)*
  ): Unit = {
    val mappings = getArtifactMappings(isSigned = true, gpgArgs, workspace, env, artifacts)
    log.info(s"mappings ${pprint.apply(
      mappings.map { case (a, fileSetContents) => (a, fileSetContents.keysSorted.map(_.toString)) }
    )}")
    val releases = mappings
//    val (snapshots, releases) = mappings.partition(_._1.isSnapshot)
//    if (snapshots.nonEmpty) {
//      val snapshotNames = snapshots.map(_._1)
//        .map { case Artifact(group, id, version) => s"$group:$id:$version" }
//      throw new Result.Exception(
//        s"""Publishing snapshots to Sonatype Central Portal is currently not supported by Mill.
//           |This is tracked under https://github.com/com-lihaoyi/mill/issues/4421
//           |The following snapshots will not be published:
//           |  ${snapshotNames.mkString("\n  ")}""".stripMargin
//      )
//    }
//    if (releases.isEmpty) {
//      log.error("No releases to publish to Sonatype Central.")
//      val errorMessage =
//        "No releases to publish to Sonatype Central." +
//          (if (snapshots.nonEmpty)
//             "It seems there were only snapshots to publish, which is not supported by Mill, currently."
//           else "Please check your build configuration.")
//      throw new Result.Exception(errorMessage)
//    }

    val releaseGroups = releases.groupBy(_.artifact.group)
    val wd = os.pwd / "out/publish-central"
    os.makeDir.all(wd)

    singleBundleName.fold {
      for ((_, groupReleases) <- releaseGroups) {
        groupReleases.foreach { case (artifact, data) =>
          val fileNameWithoutExtension = s"${artifact.group}-${artifact.id}-${artifact.version}"
          val zipFile = streamToFile(fileNameWithoutExtension, wd) { outputStream =>
            log.info(s"bundle $fileNameWithoutExtension with ${pprint.apply(data.keysSorted.map(_.toString))}")
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
      files: FileSetContents.Bytes,
      jarOutputStream: JarOutputStream
  ): Unit = {
    files.contents.foreach { case (filename, fileAsBytes) =>
      val zipEntry = new ZipEntry(filename.toString)
      jarOutputStream.putNextEntry(zipEntry)
      jarOutputStream.write(fileAsBytes.bytesUnsafe)
      jarOutputStream.closeEntry()
    }
  }
}
