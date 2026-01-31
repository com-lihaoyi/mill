package mill.javalib

import com.lumidion.sonatype.central.client.core.{
  DeploymentName,
  PublishingType as STPubType,
  SonatypeCredentials as STCreds
}
import com.lumidion.sonatype.central.client.requests.SyncSonatypeClient
import mill.api.Logger
import mill.javalib.api.PgpWorkerApi
import mill.javalib.internal.PublishModule.GpgArgs
import mill.javalib.publish.SonatypeHelpers
import mill.javalib.publish.{Artifact, PublishingType, SonatypeCredentials}

/**
 * Publishing logic for the standard Sonatype Central repository `central.sonatype.org`.
 *
 * Uses the PGP worker for signing.
 */
class SonatypeCentralPublisher2(
    credentials: SonatypeCredentials,
    gpgArgs: GpgArgs,
    pgpWorker: PgpWorkerApi,
    readTimeout: Int,
    connectTimeout: Int,
    log: Logger,
    workspace: os.Path,
    env: Map[String, String],
    awaitTimeout: Int
) {
  private val sonatypeCentralClient =
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

  def publishAll(
      publishingType: PublishingType,
      singleBundleName: Option[String],
      artifacts: (Map[os.SubPath, os.Path], Artifact)*
  ): Unit = {
    val prepared = SonatypeCentralPublisherSupport.prepareToPublishAll(
      singleBundleName,
      artifacts.toSeq,
      mapArtifacts = SonatypeHelpers.getArtifactMappings(isSigned = true, gpgArgs, env, pgpWorker, _),
      log = log
    )
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
    val prepared = SonatypeCentralPublisherSupport.prepareToPublishAll(
      singleBundleName,
      artifacts.toSeq,
      mapArtifacts = SonatypeHelpers.getArtifactMappings(isSigned = true, gpgArgs, env, pgpWorker, _),
      log = log
    )
    log.info(prepared.mappingsString)
    SonatypeCentralPublisherSupport.publishAllToLocal(prepared, publishTo, log)
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
        throw new RuntimeException(
          s"Failed to publish ${deploymentName.unapply} to Sonatype Central",
          ex
        )
      }
    }

    log.info(s"Successfully published ${deploymentName.unapply} to Sonatype Central")
  }

}
