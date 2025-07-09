package mill.javalib.maven.worker.impl

import ch.qos.logback.classic.{Level, Logger}
import mill.javalib.MavenWorkerSupport
import mill.javalib.MavenWorkerSupport.RemoteM2Publisher
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

//noinspection ScalaUnusedSymbol - invoked dynamically as a worker.
class WorkerImpl extends MavenWorkerSupport.Api {
  def publishToRemote(
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

    RemoteM2Publisher.DeployResult(
      artifacts = deployResult.getArtifacts.iterator().asScala.map(_.toString).toVector,
      metadatas = deployResult.getMetadata.iterator().asScala.map(_.toString).toVector
    )
  }

  private def withQuietLogging[T](
      loggers: Seq[String],
      level: Level = Level.INFO
  )(body: => T): T = {
    val originalLevels = loggers.map { loggerName =>
      val logger = LoggerFactory.getLogger(loggerName).asInstanceOf[Logger]
      (logger = logger, level = logger.getLevel)
    }

    try {
      originalLevels.foreach(_.logger.setLevel(level))
      body
    } finally {
      originalLevels.foreach(t => t.logger.setLevel(t.level))
    }
  }
}
