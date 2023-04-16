package mill.contrib.gitlab

import mill._
import mill.api.Result.{Failure, Success}
import mill.api.Result
import mill.define.{Command, ExternalModule, Task}
import mill.scalalib.publish.Artifact
import scalalib._

trait GitlabPublishModule extends PublishModule { outer =>

  def publishRepository: ProjectRepository

  def skipPublish: Boolean = false

  def tokenLookup: GitlabTokenLookup = new GitlabTokenLookup {}

  def gitlabHeaders(
      systemProps: Map[String, String] = sys.props.toMap
  ): Task[GitlabAuthHeaders] = T.task {
    val auth = tokenLookup.resolveGitlabToken(T.env, systemProps, T.workspace)
    auth match {
      case Left(msg) =>
        Failure(
          s"Token lookup for PUBLISH repository ($publishRepository) failed with $msg"
        ): Result[GitlabAuthHeaders]
      case Right(value) => Success(value)
    }
  }

  def publishGitlab(
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ): define.Command[Unit] = T.command {

    val gitlabRepo = publishRepository

    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    if (skipPublish) {
      T.log.info(s"SkipPublish = true, skipping publishing of $artifactInfo")
    } else {
      val uploader = new GitlabUploader(gitlabHeaders()(), readTimeout, connectTimeout)
      new GitlabPublisher(
        uploader.upload,
        gitlabRepo,
        T.log
      ).publish(artifacts.map { case (a, b) => (a.path, b) }, artifactInfo)
    }

  }
}

object GitlabPublishModule extends ExternalModule {

  def publishAll(
      personalToken: String,
      gitlabRoot: String,
      projectId: Int,
      publishArtifacts: mill.main.Tasks[PublishModule.PublishData],
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ): Command[Unit] = T.command {
    val repo = ProjectRepository(gitlabRoot, projectId)
    val auth = GitlabAuthHeaders.privateToken(personalToken)

    val artifacts: Seq[(Seq[(os.Path, String)], Artifact)] =
      T.sequence(publishArtifacts.value)().map {
        case PublishModule.PublishData(a, s) => (s.map { case (p, f) => (p.path, f) }, a)
      }

    val uploader = new GitlabUploader(auth, readTimeout, connectTimeout)

    new GitlabPublisher(
      uploader.upload,
      repo,
      T.log
    ).publishAll(
      artifacts: _*
    )
  }

  import mill.main.TokenReaders._

  lazy val millDiscover: mill.define.Discover[this.type] = mill.define.Discover[this.type]
}
