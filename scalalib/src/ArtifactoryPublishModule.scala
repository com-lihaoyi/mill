package mill
package scalalib

import define.ExternalModule
import publish.{Artifact, ArtifactoryPublisher}

trait ArtifactoryPublishModule extends PublishModule {
  def artifactoryUri: String

  def artifactorySnapshotUri: String

  def publishArtifactory(artifactoryCreds: String,
                         readTimeout: Int = 60000,
                         connectTimeout: Int = 5000): define.Command[Unit] = T.command {
    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    new ArtifactoryPublisher(
      artifactoryUri,
      artifactorySnapshotUri,
      artifactoryCreds,
      readTimeout,
      connectTimeout,
      T.log
    ).publish(artifacts.map{case (a, b) => (a.path, b)}, artifactInfo)
  }
}

object ArtifactoryPublishModule extends ExternalModule {
  def publishAll(artifactoryCreds: String,
                 artifactoryUri: String,
                 artifactorySnapshotUri: String,
                 publishArtifacts: mill.main.Tasks[PublishModule.PublishData],
                 readTimeout: Int = 60000,
                 connectTimeout: Int = 5000) = T.command {

    val x: Seq[(Seq[(os.Path, String)], Artifact)] = T.sequence(publishArtifacts.value)().map{
      case PublishModule.PublishData(a, s) => (s.map{case (p, f) => (p.path, f)}, a)
    }
    new ArtifactoryPublisher(
      artifactoryUri,
      artifactorySnapshotUri,
      artifactoryCreds,
      readTimeout,
      connectTimeout,
      T.log
    ).publishAll(
      x:_*
    )
  }

  implicit def millScoptTargetReads[T] = new mill.main.Tasks.Scopt[T]()

  lazy val millDiscover: mill.define.Discover[this.type] = mill.define.Discover[this.type]
}
