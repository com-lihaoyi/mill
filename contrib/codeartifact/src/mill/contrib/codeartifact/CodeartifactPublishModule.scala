package mill.contrib.codeartifact

import mill._
import javalib._
import mill.api.ExternalModule

trait CodeartifactPublishModule extends PublishModule {
  def codeartifactUri: String

  def codeartifactSnapshotUri: String

  def publishCodeartifact(
      credentials: String,
      publish: Boolean = true,
      codeartifactUri: String = codeartifactUri,
      codeartifactSnapshotUri: String = codeartifactSnapshotUri,
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ): Command[Unit] =
    Task.Command {
      val (artifacts, artifactInfo) = publishArtifacts().withConcretePath

      new CodeartifactPublisher(
        codeartifactUri,
        codeartifactSnapshotUri,
        credentials,
        readTimeout,
        connectTimeout,
        Task.log
      ).publish(artifacts, artifactInfo)
    }
}

object CodeartifactPublishModule extends ExternalModule {
  def publishAll(
      credentials: String,
      codeartifactUri: String,
      codeartifactSnapshotUri: String,
      publishArtifacts: mill.util.Tasks[PublishModule.PublishData],
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ) =
    Task.Command {
      val artifacts = Task.sequence(publishArtifacts.value)().map(_.withConcretePath)
      new CodeartifactPublisher(
        codeartifactUri,
        codeartifactSnapshotUri,
        credentials,
        readTimeout,
        connectTimeout,
        Task.log
      ).publishAll(
        artifacts*
      )
    }

  lazy val millDiscover: mill.api.Discover =
    mill.api.Discover[this.type]
}
