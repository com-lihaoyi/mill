package mill.scalalib.maven.worker.impl

import ch.qos.logback.classic.{Level, Logger}
import mill.scalalib.internal
import mill.scalalib.MavenWorkerSupport.RemoteM2Publisher
import org.slf4j.LoggerFactory
import os.Path

import scala.jdk.CollectionConverters.*

//noinspection ScalaUnusedSymbol - invoked dynamically as a worker.
class WorkerImpl extends internal.MavenWorkerSupport.Api {
  override def publishToRemote(
      uri: String,
      workspace: os.Path,
      username: String,
      password: String,
      artifacts: IterableOnce[RemoteM2Publisher.M2Artifact]
  ): RemoteM2Publisher.DeployResult = {
    // Aether logs everything that happens on the wire in DEBUG log level, so we want to silence that.
    val deployResult = withQuietLogging(List("org.apache.http")) {
      WorkerRemoteM2Publisher.publish(
        uri = uri,
        workspace = workspace,
        username = username,
        password = password,
        artifacts = artifacts.iterator.map(WorkerRemoteM2Publisher.asM2Artifact)
      )
    }

    WorkerImpl.deployResultFromMaven(deployResult)
  }

  override def publishToLocal(
      publishTo: Path,
      workspace: Path,
      artifacts: IterableOnce[RemoteM2Publisher.M2Artifact]
  ): RemoteM2Publisher.DeployResult = {
    val deployResult = WorkerRemoteM2Publisher.publishLocal(
      publishTo = publishTo,
      workspace = workspace,
      artifacts = artifacts.iterator.map(WorkerRemoteM2Publisher.asM2Artifact)
    )

    WorkerImpl.deployResultFromMaven(deployResult)
  }

  private def withQuietLogging[T](
      loggers: Seq[String],
      level: Level = Level.INFO
  )(body: => T): T = {
    val originalLevels = loggers.map { loggerName =>
      val logger = LoggerFactory.getLogger(loggerName).asInstanceOf[Logger]
      (logger, logger.getLevel)
    }

    try {
      originalLevels.foreach(_._1.setLevel(level))
      body
    } finally {
      originalLevels.foreach(t => t._1.setLevel(t._2))
    }
  }
}
object WorkerImpl {
  def deployResultFromMaven(deployResult: org.eclipse.aether.deployment.DeployResult)
      : RemoteM2Publisher.DeployResult =
    RemoteM2Publisher.DeployResult(
      artifacts = deployResult.getArtifacts.iterator().asScala.map(_.toString).toVector,
      metadatas = deployResult.getMetadata.iterator().asScala.map(_.toString).toVector
    )
}
