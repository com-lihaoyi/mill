package mill.javalib

import com.lumidion.sonatype.central.client.core.SonatypeCredentials as STCreds
import com.lumidion.sonatype.central.client.requests.SyncSonatypeClient
import mill.api.Logger
import mill.javalib.publish.{Artifact, PublishingType, SonatypeCredentials}
import mill.javalib.internal.PublishModule.GpgArgs
import mill.javalib.publish.SonatypeHelpers

private[mill] abstract class SonatypeCentralPublisherBase private[mill] (
    credentials: SonatypeCredentials,
    gpgArgs: GpgArgs,
    readTimeout: Int,
    connectTimeout: Int,
    log: Logger,
    env: Map[String, String],
    awaitTimeout: Int
) {
  protected final val sonatypeCentralClient: SyncSonatypeClient =
    new SyncSonatypeClient(
      credentials = STCreds(credentials.username, credentials.password),
      readTimeout = readTimeout,
      connectTimeout = connectTimeout
    )

  protected def mapArtifacts(
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)]
  ): Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])]

  private[mill] final def publishInternal(
      fileMapping: Map[os.SubPath, os.Path],
      artifact: Artifact,
      publishingType: PublishingType
  ): Unit =
    publishAllInternal(publishingType, singleBundleName = None, Seq(fileMapping -> artifact))

  private[mill] final def publishAllInternal(
      publishingType: PublishingType,
      singleBundleName: Option[String],
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)]
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

  private[mill] final def publishAllToLocalInternal(
      publishTo: os.Path,
      singleBundleName: Option[String],
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)]
  ): Unit =
    SonatypeHelpers.publishAllToLocal(
      singleBundleName,
      artifacts,
      mapArtifacts = mapArtifacts,
      publishTo = publishTo,
      log = log
    )
}
