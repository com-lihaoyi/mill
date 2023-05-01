package mill.contrib.codeartifact

import mill._, scalalib._, define.ExternalModule, publish.Artifact

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
  ): define.Command[Unit] =
    T.command {
      val PublishModule.PublishData(artifactInfo, artifacts) =
        publishArtifacts()

      new CodeartifactPublisher(
        codeartifactUri,
        codeartifactSnapshotUri,
        credentials,
        readTimeout,
        connectTimeout,
        T.log
      ).publish(artifacts.map { case (a, b) => (a.path, b) }, artifactInfo)
    }
}

object CodeartifactPublishModule extends ExternalModule {
  def publishAll(
      credentials: String,
      codeartifactUri: String,
      codeartifactSnapshotUri: String,
      publishArtifacts: mill.main.Tasks[PublishModule.PublishData],
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ) =
    T.command {

      val x: Seq[(Seq[(os.Path, String)], Artifact)] =
        T.sequence(publishArtifacts.value)().map {
          case PublishModule.PublishData(a, s) =>
            (s.map { case (p, f) => (p.path, f) }, a)
        }
      new CodeartifactPublisher(
        codeartifactUri,
        codeartifactSnapshotUri,
        credentials,
        readTimeout,
        connectTimeout,
        T.log
      ).publishAll(
        x: _*
      )
    }

  lazy val millDiscover: mill.define.Discover[this.type] =
    mill.define.Discover[this.type]
}
