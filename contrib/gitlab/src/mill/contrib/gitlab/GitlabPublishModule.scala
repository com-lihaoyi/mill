package mill.contrib.gitlab

import mill._
import mill.api.Result.{Failure, Success}
import mill.api.Result
import mill.api.{ExternalModule, Task}
import javalib._

trait GitlabPublishModule extends PublishModule { outer =>

  def publishRepository: ProjectRepository

  def skipPublish: Boolean = false

  def tokenLookup: GitlabTokenLookup = new GitlabTokenLookup {}

  def gitlabHeaders(
      systemProps: Map[String, String] = sys.props.toMap
  ): Task[GitlabAuthHeaders] = Task.Anon {
    val auth =
      tokenLookup.resolveGitlabToken(Task.env, systemProps, mill.api.BuildCtx.workspaceRoot)
    auth match {
      case f: Result.Failure =>
        Failure(
          s"Token lookup for PUBLISH repository ($publishRepository) failed with ${f.error}"
        ): Result[GitlabAuthHeaders]
      case Result.Success(value) => Success(value)
    }
  }

  def publishGitlab(
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ): Command[Unit] = Task.Command {

    val gitlabRepo = publishRepository

    val (artifacts, artifactInfo) = publishArtifacts().withConcretePath
    if (skipPublish) {
      Task.log.info(s"SkipPublish = true, skipping publishing of $artifactInfo")
    } else {
      val uploader = GitlabUploader(gitlabHeaders()(), readTimeout, connectTimeout)
      GitlabPublisher(
        uploader.upload,
        gitlabRepo,
        Task.log
      ).publish(artifacts, artifactInfo)
    }

  }
}

object GitlabPublishModule extends ExternalModule {

  def publishAll(
      personalToken: String,
      gitlabRoot: String,
      projectId: Int,
      publishArtifacts: mill.util.Tasks[PublishModule.PublishData],
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ): Command[Unit] = Task.Command {
    val repo = ProjectRepository(gitlabRoot, projectId)
    val auth = GitlabAuthHeaders.privateToken(personalToken)

    val artifacts = Task.sequence(publishArtifacts.value)().map {
      case data @ PublishModule.PublishData(_, _) => data.withConcretePath
    }
    val uploader = GitlabUploader(auth, readTimeout, connectTimeout)

    GitlabPublisher(
      uploader.upload,
      repo,
      Task.log
    ).publishAll(
      artifacts*
    )
  }

  lazy val millDiscover: mill.api.Discover = mill.api.Discover[this.type]
}
