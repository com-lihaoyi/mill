package mill.javalib

import com.lumidion.sonatype.central.client.core.{DeploymentName, SonatypeCredentials as STCreds}
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
) extends SonatypeCentralPublisherBase(
      credentials = credentials,
      gpgArgs = gpgArgs,
      readTimeout = readTimeout,
      connectTimeout = connectTimeout,
      log = log,
      env = env,
      awaitTimeout = awaitTimeout
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

  override protected def mapArtifacts(
      artifacts: Seq[(Map[os.SubPath, os.Path], Artifact)]
  ): Seq[(artifact: Artifact, contents: Map[os.SubPath, Array[Byte]])] =
    SonatypeHelpers.getArtifactMappings(isSigned = true, gpgArgs, env, artifacts)

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
    publishInternal(fileMapping, artifact, publishingType)

  def publishAll(
      publishingType: PublishingType,
      singleBundleName: Option[String],
      artifacts: (Seq[(os.Path, String)], Artifact)*
  ): Unit = {
    publishAll(publishingType, singleBundleName, SonatypeHelpers.mapArtifacts(artifacts*)*)
  }

  @targetName("publishAllByMap")
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
