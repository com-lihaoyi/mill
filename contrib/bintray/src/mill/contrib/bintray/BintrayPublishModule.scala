package mill.contrib.bintray

import mill._, scalalib._, define.ExternalModule, publish.Artifact

trait BintrayPublishModule extends PublishModule {
  def bintrayOwner: String

  def bintrayRepo: String

  def publishBintray(credentials: String,
                     bintrayOwner: String = bintrayOwner,
                     bintrayRepo: String = bintrayRepo,
                     readTimeout: Int = 60000,
                     connectTimeout: Int = 5000): define.Command[Unit] = T.command {
    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    new BintrayPublisher(
      bintrayOwner,
      bintrayRepo,
      credentials,
      readTimeout,
      connectTimeout,
      T.log
    ).publish(artifacts.map{case (a, b) => (a.path, b)}, artifactInfo)
  }
}

object BintrayPublishModule extends ExternalModule {
  def publishAll(credentials: String,
                 bintrayOwner: String,
                 bintrayRepo: String,
                 publishArtifacts: mill.main.Tasks[PublishModule.PublishData],
                 readTimeout: Int = 60000,
                 connectTimeout: Int = 5000) = T.command {

    val x: Seq[(Seq[(os.Path, String)], Artifact)] = T.sequence(publishArtifacts.value)().map{
      case PublishModule.PublishData(a, s) => (s.map{case (p, f) => (p.path, f)}, a)
    }
    new BintrayPublisher(
      bintrayOwner,
      bintrayRepo,
      credentials,
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
