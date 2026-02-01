package mill.javalib

import com.lumidion.sonatype.central.client.core.SonatypeCredentials as STCreds
import com.lumidion.sonatype.central.client.requests.SyncSonatypeClient
import mill.api.Logger
import mill.javalib.publish.{Artifact, PublishingType, SonatypeCredentials}
import mill.javalib.internal.PublishModule.GpgArgs
import mill.javalib.publish.SonatypeHelpers

import scala.annotation.{targetName, unused}

private[mill] abstract class SonatypeCentralPublisherBase private[mill] (
    credentials: SonatypeCredentials,
    @unused gpgArgs: GpgArgs,
    readTimeout: Int,
    connectTimeout: Int,
    log: Logger,
    @unused env: Map[String, String],
    awaitTimeout: Int
) {
  protected final val sonatypeCentralClient: SyncSonatypeClient =
    new SyncSonatypeClient(
      credentials = STCreds(credentials.username, credentials.password),
      readTimeout = readTimeout,
      connectTimeout = connectTimeout
    )

  def publish(
      fileMapping: Map[os.SubPath, os.Path],
      artifact: Artifact,
      publishingType: PublishingType
  ): Unit =
    publishAll(publishingType, singleBundleName = None, fileMapping -> artifact)

  @targetName("publishAllByMap")
  def publishAll(
      publishingType: PublishingType,
      singleBundleName: Option[String],
      artifacts: (Map[os.SubPath, os.Path], Artifact)*
  ): Unit = {
    SonatypeHelpers.publishAll(
      singleBundleName,
      artifacts,
      mapArtifacts = mapArtifacts,
      log = log
    ) { (zipFile, deploymentName) =>
      SonatypeHelpers.publishBundle(
        sonatypeCentralClient,
        zipFile,
        deploymentName,
        publishingType,
        awaitTimeout,
        log
      )
    }
  }

  private[mill] def publishAllToLocal(
      publishTo: os.Path,
      singleBundleName: Option[String],
      artifacts: (Map[os.SubPath, os.Path], Artifact)*
  ): Unit = {
    SonatypeHelpers.publishAllToLocal(
      singleBundleName,
      artifacts,
      mapArtifacts = mapArtifacts,
      publishTo = publishTo,
      log = log
    )
  }

  protected def mapArtifacts(
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)]
  ): Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])]

}
