package mill.javalib

import com.lumidion.sonatype.central.client.core.DeploymentName
import mill.api.Logger
import mill.javalib.publish.Artifact

import java.io.File
import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

private[mill] object SonatypeCentralPublisherSupport {
  final case class PreparedArtifacts(
      mappings: Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])],
      deployments: Vector[(zipFile: File, deploymentName: DeploymentName)]
  ) {
    def mappingsString: String = s"mappings ${pprint(
        mappings.map { case (a, fileSetContents) =>
          (a, fileSetContents.keys.toVector.sorted.map(_.toString))
        }
      )}"
  }

  def prepareToPublishAll(
      singleBundleName: Option[String],
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)],
      mapArtifacts: Seq[(Map[os.SubPath, os.Path], Artifact)] =>
        Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])],
      log: Logger
  ): PreparedArtifacts = {
    val releases = mapArtifacts(artifacts)

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

  def publishAllToLocal(
      prepared: PreparedArtifacts,
      publishTo: os.Path,
      log: Logger
  ): Unit = {
    prepared.deployments.foreach { case (zipFile, deploymentName) =>
      val target = publishTo / deploymentName.unapply
      log.info(s"Unzipping $zipFile to $target")
      os.makeDir.all(target)
      os.unzip(os.Path(zipFile.toPath), target)
    }
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
      files: Map[os.SubPath, Array[Byte]],
      jarOutputStream: JarOutputStream
  ): Unit = {
    files.foreach { case (filename, fileAsBytes) =>
      val zipEntry = new ZipEntry(filename.toString)
      jarOutputStream.putNextEntry(zipEntry)
      jarOutputStream.write(fileAsBytes)
      jarOutputStream.closeEntry()
    }
  }
}
