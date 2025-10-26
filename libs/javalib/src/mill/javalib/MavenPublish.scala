package mill.javalib

import com.lumidion.sonatype.central.client.core.SonatypeCredentials
import mill.api.daemon.Logger
import mill.javalib.PublishModule.PublishData
import mill.javalib.internal.MavenWorkerSupport as InternalMavenWorkerSupport

private[mill] trait MavenPublish {

  def mavenPublishDatas(
      publishDatas: Seq[PublishData],
      bundleName: Option[String],
      credentials: SonatypeCredentials,
      releaseUri: String,
      snapshotUri: String,
      taskDest: os.Path,
      log: Logger,
      env: Map[String, String],
      worker: InternalMavenWorkerSupport.Api
  ): Unit = {
    val dryRun = env.get("MILL_TESTS_PUBLISH_DRY_RUN").contains("1")

    val (snapshots, releases) = publishDatas.partition(_.meta.isSnapshot)

    bundleName.filter(_ => snapshots.nonEmpty).foreach { bundleName =>
      throw new IllegalArgumentException(
        s"Publishing SNAPSHOT versions when bundle name ($bundleName) is specified is not supported.\n\n" +
          s"SNAPSHOT versions: ${pprint.apply(snapshots)}"
      )
    }

    releases.map(_ -> false).appendedAll(snapshots.map(_ -> true)).foreach { (data, isSnapshot) =>
      mavenPublishData(
        dryRun = dryRun,
        publishData = data,
        isSnapshot = isSnapshot,
        credentials = credentials,
        releaseUri = releaseUri,
        snapshotUri = snapshotUri,
        taskDest = taskDest,
        log = log,
        worker = worker
      )
    }
  }

  def mavenPublishData(
      dryRun: Boolean,
      publishData: PublishData,
      isSnapshot: Boolean,
      credentials: SonatypeCredentials,
      releaseUri: String,
      snapshotUri: String,
      taskDest: os.Path,
      log: Logger,
      worker: InternalMavenWorkerSupport.Api
  ): Unit = {
    val uri = if (isSnapshot) snapshotUri else releaseUri
    val artifacts = MavenWorkerSupport.RemoteM2Publisher.asM2ArtifactsFromPublishDatas(
      publishData.meta,
      publishData.payloadAsMap
    )

    if (isSnapshot) {
      log.info(
        s"Detected a 'SNAPSHOT' version for ${publishData.meta}, publishing to Maven Repository at '$uri'"
      )
    }

    /** Maven uses this as a workspace for file manipulation. */
    val mavenWorkspace = taskDest / "maven"

    if (dryRun) {
      val publishTo = taskDest / "repository"
      val result = worker.publishToLocal(
        publishTo = publishTo,
        workspace = mavenWorkspace,
        artifacts
      )
      log.info(s"Dry-run publishing to '$publishTo' finished with result: $result")
    } else {
      val result = worker.publishToRemote(
        uri = uri,
        workspace = mavenWorkspace,
        username = credentials.username,
        password = credentials.password,
        artifacts
      )
      log.info(s"Publishing to '$uri' finished with result: $result")
    }
  }

}
