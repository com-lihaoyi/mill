package mill.scalalib.publish

import com.lumidion.sonatype.central.client.core.{
  DeploymentName,
  PublishingType,
  SonatypeCredentials
}
import com.lumidion.sonatype.central.client.requests.SyncSonatypeClient
import mill.api.Logger

import java.io.FileOutputStream
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
  private val sonatypeCentralClient =
    new SyncSonatypeClient(credentials, readTimeout = readTimeout, connectTimeout = connectTimeout)

  def publish(fileMapping: Seq[(os.Path, String)], artifact: Artifact, release: Boolean): Unit = {
    publishAll(release, fileMapping -> artifact)
  }

  def publishAll(release: Boolean, artifacts: (Seq[(os.Path, String)], Artifact)*): Unit = {
    val mappings = getArtifactMappings(signed, gpgArgs, workspace, env, artifacts)

    val (_, releases) = mappings.partition(_._1.isSnapshot)

    val releaseGroups = releases.groupBy(_._1.group)
    val wd = os.pwd / "out" / "publish-central"
    os.makeDir.all(wd)

    for ((_, groupReleases) <- releaseGroups) {
      groupReleases.foreach { case (artifact, data) =>
        val jarFile =
          (wd / s"${artifact.group}-${artifact.id}-${artifact.version}.jar").toIO
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

        try {
          sonatypeCentralClient.uploadBundleFromFile(
            jarFile,
            DeploymentName.fromArtifact(
              artifact.group,
              artifact.id,
              artifact.version
            ),
            Some(PublishingType.USER_MANAGED),
            timeout = awaitTimeout
          )
        } catch {
          case ex: Throwable => {
            throw new RuntimeException(
              s"Failed to publish ${artifact.id} to Sonatype. Error: \n${ex.getMessage}"
            )
          }

        }

        log.info(s"Successfully published ${artifact.id} to Sonatype")
      }
    }
  }
}
