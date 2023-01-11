package mill.contrib.gitlab

import coursier.core.Authentication
import coursier.maven.MavenRepository
import mill._
import mill.api.Result.{Failure, Success}
import mill.api.Result
import mill.define.{Command, ExternalModule, Task}
import mill.scalalib.publish.Artifact
import scalalib._

trait GitlabMavenRepository extends ScalaModule {

  def tokenLookup: GitlabTokenLookup = new GitlabTokenLookup {} // For token discovery
  def gitlabRepository: GitlabPackageRepository // For package discovery

  def mavenRepository: Task[MavenRepository] = T.task {

    val gitlabAuth = tokenLookup.resolveGitlabToken(T.env.get, sys.props.get)()
      .map(auth => Authentication(auth.headers))
      .map(auth => MavenRepository(gitlabRepository.url(), Some(auth)))

    gitlabAuth match {
      case Left(msg) =>
        Failure(
          s"Token lookup for PACKAGE repository ($gitlabRepository) failed with $msg"
        ): Result[MavenRepository]
      case Right(value) => Success(value)
    }
  }
}

trait GitlabPublishModule extends PublishModule {

  def publishRepository: ProjectRepository

  def skipPublish: Boolean = false

  def tokenLookup: GitlabTokenLookup = new GitlabTokenLookup {}

  def gitlabHeaders(
      systemProps: Map[String, String] = sys.props.toMap
  ): Task[GitlabAuthHeaders] = T.task {
    val auth = tokenLookup.resolveGitlabToken(T.env.get, systemProps.get)()
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
      val uploader = new GitlabUploader(gitlabHeaders()())
      new GitlabPublisher(
        uploader.upload,
        gitlabRepo,
        readTimeout,
        connectTimeout,
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

    val uploader = new GitlabUploader(auth)

    new GitlabPublisher(
      uploader.upload,
      repo,
      readTimeout,
      connectTimeout,
      T.log
    ).publishAll(
      artifacts: _*
    )
  }

  implicit def millScoptTargetReads[T] = new mill.main.Tasks.Scopt[T]()

  lazy val millDiscover: mill.define.Discover[this.type] = mill.define.Discover[this.type]
}
