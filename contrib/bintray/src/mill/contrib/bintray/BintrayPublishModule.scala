package mill.contrib.bintray

import mill._, scalalib._, define.ExternalModule, publish.Artifact
import mill.contrib.bintray.BintrayPublishModule.BintrayPublishData

trait BintrayPublishModule extends PublishModule {

  def bintrayOwner: String

  def bintrayRepo: String

  def bintrayPackage = T { artifactId() }

  def bintrayPublishArtifacts: T[BintrayPublishData] = T {
    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    BintrayPublishData(artifactInfo, artifacts, bintrayPackage())
  }

  def publishBintray(credentials: String,
                     bintrayOwner: String = bintrayOwner,
                     bintrayRepo: String = bintrayRepo,
                     bintrayPackage: T[String] = bintrayPackage,
                     release: Boolean = true,
                     readTimeout: Int = 60000,
                     connectTimeout: Int = 5000): define.Command[Unit] = T.command {
    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    new BintrayPublisher(
      bintrayOwner,
      bintrayRepo,
      credentials,
      release,
      readTimeout,
      connectTimeout,
      T.log
    ).publish(artifacts.map{case (a, b) => (a.path, b)}, artifactInfo, bintrayPackage())
  }
}

object BintrayPublishModule extends ExternalModule {

  case class BintrayPublishData(meta: Artifact, payload: Seq[(PathRef, String)], bintrayPackage: String)

  object BintrayPublishData{
    implicit def jsonify: upickle.default.ReadWriter[BintrayPublishData] = upickle.default.macroRW
  }

  def publishAll(credentials: String,
                 bintrayOwner: String,
                 bintrayRepo: String,
                 release: Boolean = true,
                 publishArtifacts: mill.main.Tasks[BintrayPublishData],
                 readTimeout: Int = 60000,
                 connectTimeout: Int = 5000) = T.command {

    val x: Seq[((Seq[(os.Path, String)], Artifact), String)] = T.sequence(publishArtifacts.value)().map{
      case BintrayPublishData(meta, payload, pkg) => payload.map { case (p, f) => (p.path, f) } -> meta -> pkg
    }
    new BintrayPublisher(
      bintrayOwner,
      bintrayRepo,
      credentials,
      release,
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
