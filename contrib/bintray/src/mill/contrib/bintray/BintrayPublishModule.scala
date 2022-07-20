package mill.contrib.bintray

import mill._, scalalib._, define.ExternalModule, publish.Artifact

trait BintrayPublishModule extends PublishModule {

  def bintrayOwner: String

  def bintrayRepo: String

  def bintrayPackage = T { artifactId() }

  def bintrayPublishArtifacts: T[BintrayPublishData] = T {
    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    BintrayPublishData(artifactInfo, artifacts, bintrayPackage())
  }

  def publishBintray(
      credentials: String,
      bintrayOwner: String = bintrayOwner,
      bintrayRepo: String = bintrayRepo,
      release: Boolean = true,
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ): define.Command[Unit] = T.command {
    new BintrayPublisher(
      bintrayOwner,
      bintrayRepo,
      credentials,
      release,
      readTimeout,
      connectTimeout,
      T.log
    ).publish(bintrayPublishArtifacts())
  }
}

object BintrayPublishModule extends ExternalModule {

  def publishAll(
      credentials: String,
      bintrayOwner: String,
      bintrayRepo: String,
      release: Boolean = true,
      publishArtifacts: mill.main.Tasks[BintrayPublishData],
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ) = T.command {
    new BintrayPublisher(
      bintrayOwner,
      bintrayRepo,
      credentials,
      release,
      readTimeout,
      connectTimeout,
      T.log
    ).publishAll(
      T.sequence(publishArtifacts.value)(): _*
    )
  }

  implicit def millScoptTargetReads[T] = new mill.main.Tasks.Scopt[T]()

  lazy val millDiscover: mill.define.Discover[this.type] = mill.define.Discover[this.type]
}
