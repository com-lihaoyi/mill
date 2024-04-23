package mill.scalalib.publish

import com.lumidion.sonatype.central.client.core.{DeploymentName, PublishingType, SonatypeCredentials}
import com.lumidion.sonatype.central.client.requests.SyncSonatypeClient
import mill.api.Logger

import java.io.FileOutputStream
import java.nio.file.Path
import java.util.UUID
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class SonatypeCentralPublisher(
    credentials: SonatypeCredentials,
    signed: Boolean,
    gpgArgs: Seq[String],
    readTimeout: Int,
    connectTimeout: Int,
    log: Logger,
    workspace: os.Path,
    env: Map[String, String],
    awaitTimeout: Int
) extends SonatypeHelpers {
  private val sonatypeCentralClient = new SyncSonatypeClient(credentials)

  def publish(fileMapping: Seq[(os.Path, String)], artifact: Artifact, release: Boolean): Unit = {
    publishAll(release, fileMapping -> artifact)
  }

  private val id = UUID.randomUUID().toString

  def publishAll(release: Boolean, artifacts: (Seq[(os.Path, String)], Artifact)*): Unit = {
    val mappings = getArtifactMappings(signed, gpgArgs, workspace, env, artifacts)

    val (_, releases) = mappings.partition(_._1.isSnapshot)

    val releaseGroups = releases.groupBy(_._1.group)
    val wd = os.pwd / "out" / "publish-central"
    println(s"Is release set - $release")
    println(s"Removing all now - $id")
    os.remove.all(wd)
    println("All removed")
    os.makeDir.all(wd)

    for ((_, groupReleases) <- releaseGroups) {
      groupReleases.foreach { case (art, data) =>
        val jarFile =
          (wd / s"${art.group}-${art.id}-${art.version}.jar").toIO
        val fileOutputStream = new FileOutputStream(jarFile)
        val jarOutputStream = new JarOutputStream(fileOutputStream)

        try {
          data.foreach { case (filename, fileAsBytes) =>
            val zipEntry = new ZipEntry(filename)
            jarOutputStream.putNextEntry(zipEntry)
            jarOutputStream.write(fileAsBytes)
            jarOutputStream.closeEntry()
          }
        } finally {
          jarOutputStream.close()
        }

//        sonatypeCentralClient.uploadBundleFromFile(
//          jarFile,
//          DeploymentName.fromArtifact(
//            art.group,
//            art.id,
//            art.version
//          ),
//          Some(PublishingType.USER_MANAGED)
//        )
      }
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
}
