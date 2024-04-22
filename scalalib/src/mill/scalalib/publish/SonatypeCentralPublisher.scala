package mill.scalalib.publish

import com.lumidion.sonatype.central.client.core.{
  DeploymentName,
  PublishingType,
  SonatypeCredentials
}
import com.lumidion.sonatype.central.client.requests.SyncSonatypeClient
import mill.api.Logger
import org.apache.commons.io.output.ByteArrayOutputStream

import java.io.FileOutputStream
import java.nio.file.Path
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

  def publishAll(release: Boolean, artifacts: (Seq[(os.Path, String)], Artifact)*): Unit = {
    val mappings = getArtifactMappings(signed, gpgArgs, workspace, env, artifacts)

    val (_, releases) = mappings.partition(_._1.isSnapshot)

    val releaseGroups = releases.groupBy(_._1.group)
//    val wd = os.pwd / "out" / "publish-central"
//    os.remove.all(wd)
//    os.makeDir.all(wd)

    for ((group, groupReleases) <- releaseGroups) {
      println(group)
      groupReleases.foreach { case (art, data) =>
//        val jarFile =
//          Path.of(s"./out/publish-central/${art.group}-${art.id}-${art.version}.jar").toFile
        val jarFile =
          Path.of(s"./${art.group}-${art.id}-${art.version}.jar").toFile
        val fileOutputStream = new FileOutputStream(jarFile)
//        val byteArrayOutputStream = new FileOutputStream()
        val jarOutputStream = new JarOutputStream(fileOutputStream)
        println(art)
//        try {
        data.foreach { case (filename, fileAsBytes) =>
          val zipEntry = new ZipEntry(filename)
          jarOutputStream.putNextEntry(zipEntry)
          jarOutputStream.write(fileAsBytes)
          jarOutputStream.closeEntry()
        }


        jarOutputStream.close()

        val help = sonatypeCentralClient.uploadBundleFromFile(
          jarFile,
          DeploymentName.fromArtifact(
            art.group,
            art.id,
            art.version
          ),
          Some(PublishingType.USER_MANAGED)
        )

        println(help)
      }
      releases
//      if (stagingRelease) {
//        publishRelease(
//          release,
//          groupReleases.flatMap(_._2),
//          group,
//          releases.map(_._1),
//          awaitTimeout
//        )
//      } else publishReleaseNonstaging(groupReleases.flatMap(_._2), releases.map(_._1))
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
