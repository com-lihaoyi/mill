package mill.javalib

import com.lumidion.sonatype.central.client.core.SonatypeCredentials as STCreds
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
    env: Map[String, String],
    awaitTimeout: Int
) extends SonatypeCentralPublisherBase(
      credentials = credentials,
      gpgArgs = gpgArgs,
      readTimeout = readTimeout,
      connectTimeout = connectTimeout,
      log = log,
      env = env,
      awaitTimeout = awaitTimeout
    ) {
  override protected def mapArtifacts(
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)]
  ): Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])] =
    SonatypeHelpers.getArtifactMappings(isSigned = true, gpgArgs, env, pgpWorker, artifacts)

  def publish(
      fileMapping: Map[os.SubPath, os.Path],
      artifact: Artifact,
      publishingType: PublishingType
  ): Unit =
    publishInternal(fileMapping, artifact, publishingType)

  def publishAll(
      publishingType: PublishingType,
      singleBundleName: Option[String],
      artifacts: (Map[os.SubPath, os.Path], Artifact)*
  ): Unit = {
    publishAllInternal(publishingType, singleBundleName, artifacts.toSeq)
  }

  private[mill] def publishAllToLocal(
      publishTo: os.Path,
      singleBundleName: Option[String],
      artifacts: (Map[os.SubPath, os.Path], Artifact)*
  ): Unit = {
    publishAllToLocalInternal(publishTo, singleBundleName, artifacts.toSeq)
  }

}
