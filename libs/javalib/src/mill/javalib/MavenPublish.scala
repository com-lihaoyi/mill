package mill.javalib

import mill.api.daemon.Logger
import mill.javalib.PublishModule.PublishData
import mill.javalib.internal.MavenWorkerSupport as InternalMavenWorkerSupport

private[mill] trait MavenPublish {

  def mavenPublishDatas(
      publishDatas: Seq[PublishData],
      credentials: (username: String, password: String),
      releaseUri: String,
      snapshotUri: String,
      taskDest: os.Path,
      log: Logger,
      env: Map[String, String],
      worker: InternalMavenWorkerSupport.Api
  ): Unit = {
    val dryRun = env.get("MILL_TESTS_PUBLISH_DRY_RUN").contains("1")

    val (snapshots, releases) = publishDatas.partition(_.meta.isSnapshot)

    Seq(releases -> false, snapshots -> true).foreach { (datas, isSnapshot) =>
      mavenDeploy(
        dryRun = dryRun,
        publishDatas = datas,
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

  /**
   * Deploys all of the given [[PublishData]]s in a single Maven deploy operation.
   *
   * Deploying everything in one go (rather than once per module) matters for `SNAPSHOT` versions:
   * Maven derives the timestamp part of the deployed snapshot version once per deploy operation,
   * so publishing the modules one by one would give each of them a different timestamp.
   */
  def mavenDeploy(
      dryRun: Boolean,
      publishDatas: Seq[PublishData],
      isSnapshot: Boolean,
      credentials: (username: String, password: String),
      releaseUri: String,
      snapshotUri: String,
      taskDest: os.Path,
      log: Logger,
      worker: InternalMavenWorkerSupport.Api
  ): Unit = if (publishDatas.nonEmpty) {
    val uri = if (isSnapshot) snapshotUri else releaseUri
    val artifacts = publishDatas.flatMap { publishData =>
      MavenWorkerSupport.RemoteM2Publisher.asM2ArtifactsFromPublishDatas(
        publishData.meta,
        publishData.payloadAsMap
      )
    }

    if (isSnapshot) {
      log.info(
        s"Detected a 'SNAPSHOT' version for ${publishDatas.map(_.meta).mkString(", ")}, " +
          s"publishing to Maven Repository at '$uri'"
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
