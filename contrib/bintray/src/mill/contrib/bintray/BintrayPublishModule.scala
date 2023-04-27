package mill.contrib.bintray

import mill._
import mill.api.Result
import scalalib._
import mill.contrib.bintray.BintrayPublishModule.checkBintrayCreds
import mill.define.{ExternalModule, Task}

trait BintrayPublishModule extends PublishModule {

  def bintrayOwner: String

  def bintrayRepo: String

  def bintrayPackage = T { artifactId() }

  def bintrayPublishArtifacts: T[BintrayPublishData] = T {
    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    BintrayPublishData(artifactInfo, artifacts, bintrayPackage())
  }

  /**
   * Publish all given artifacts to Bintray.
   * Uses environment variables BINTRAY_USERNAME and BINTRAY_PASSWORD as
   * credentials.
   *
   * @param credentials Bintray credentials in format username:password.
   *                    If specified, environment variables will be ignored.
   *                    <i>Note: consider using environment variables over this argument due
   *                    to security reasons.</i>
   */
  def publishBintray(
      credentials: String = "",
      bintrayOwner: String = bintrayOwner,
      bintrayRepo: String = bintrayRepo,
      release: Boolean = true,
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ): define.Command[Unit] = T.command {
    new BintrayPublisher(
      bintrayOwner,
      bintrayRepo,
      checkBintrayCreds(credentials)(),
      release,
      readTimeout,
      connectTimeout,
      T.log
    ).publish(bintrayPublishArtifacts())
  }
}

object BintrayPublishModule extends ExternalModule {

  /**
   * Publish all given artifacts to Bintray.
   * Uses environment variables BINTRAY_USERNAME and BINTRAY_PASSWORD as
   * credentials.
   *
   * @param credentials Bintray credentials in format username:password.
   *                    If specified, environment variables will be ignored.
   *                    <i>Note: consider using environment variables over this argument due
   *                    to security reasons.</i>
   */
  def publishAll(
      credentials: String = "",
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
      checkBintrayCreds(credentials)(),
      release,
      readTimeout,
      connectTimeout,
      T.log
    ).publishAll(
      T.sequence(publishArtifacts.value)(): _*
    )
  }

  private def checkBintrayCreds(credentials: String): Task[String] = T.task {
    if (credentials.isEmpty) {
      (for {
        username <- T.env.get("BINTRAY_USERNAME")
        password <- T.env.get("BINTRAY_PASSWORD")
      } yield {
        Result.Success(s"$username:$password")
      }).getOrElse(
        Result.Failure(
          "Consider using BINTRAY_USERNAME/BINTRAY_PASSWORD environment variables or passing `credentials` argument"
        )
      )
    } else {
      Result.Success(credentials)
    }
  }

  lazy val millDiscover: mill.define.Discover[this.type] = mill.define.Discover[this.type]
}
