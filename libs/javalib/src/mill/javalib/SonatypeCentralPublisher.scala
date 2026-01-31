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
import mill.javalib.publish.SonatypeHelpers
import mill.javalib.publish.{Artifact, PublishingType, SonatypeCredentials}

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
    new SyncSonatypeClient(
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
      SonatypeHelpers.toSubPathMap(fileMapping),
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
    val mappedArtifacts = SonatypeHelpers.mapArtifacts(artifacts*)
    publishAll(publishingType, singleBundleName, mappedArtifacts*)
  }

  @targetName("publishAllByMap")
  def publishAll(
      publishingType: PublishingType,
      singleBundleName: Option[String],
      artifacts: (Map[os.SubPath, os.Path], Artifact)*
  ): Unit = {
    val prepared = SonatypeCentralPublisherSupport.prepareToPublishAll(
      singleBundleName,
      artifacts.toSeq,
      mapArtifacts = SonatypeHelpers.getArtifactMappings(isSigned = true, gpgArgs, env, _),
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
      mapArtifacts = SonatypeHelpers.getArtifactMappings(isSigned = true, gpgArgs, env, _),
      log = log
    )
    log.info(prepared.mappingsString)
    SonatypeCentralPublisherSupport.publishAllToLocal(prepared, publishTo, log)
  }

  // Kept for binary compatibility with prior releases.
  private case class PreparedArtifacts(
      mappings: Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])],
      deployments: Vector[(zipFile: java.io.File, deploymentName: DeploymentName)]
  ) {
    def mappingsString: String = s"mappings ${pprint(
        mappings.map { case (a, fileSetContents) =>
          (a, fileSetContents.keys.toVector.sorted.map(_.toString))
        }
      )}"
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
