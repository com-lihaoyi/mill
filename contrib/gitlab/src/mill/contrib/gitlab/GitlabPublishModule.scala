package mill.contrib.gitlab

import coursier.core.Authentication
import coursier.maven.MavenRepository
import mill._
import mill.define.{Command, ExternalModule}
import mill.scalalib.publish.Artifact
import scalalib._

class GitlabAuthenticationException(message: String) extends Exception(message)

trait GitlabPublishModule extends PublishModule {

  def publishRepository: GitlabProjectRepository

  def skipPublish: Boolean = false

  def gitlabEnvironment: GitlabEnvironment = new GitlabEnvironment {}

  def gitlabToken: GitlabToken = {
    val auth = GitlabToken.resolveGitlabToken(gitlabEnvironment)
    if (auth.isEmpty) {
      throw new GitlabAuthenticationException(
          s"Unable to resolve authentication with gitlab environment $gitlabEnvironment"
      )
    }
    auth.get
  }

  def publishGitlab(
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ): define.Command[Unit] = T.command {

    val gitlabRepo = publishRepository
    val gitlabAuth = gitlabToken

    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    if (skipPublish) {
      T.log.info(s"SkipPublish = true, skipping publishing of $artifactInfo")
    } else {
      new GitlabPublisher(
          gitlabRepo,
          gitlabAuth,
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
    val repo = GitlabProjectRepository(gitlabRoot, projectId)
    val auth = GitlabToken.personalToken(personalToken)

    val artifacts: Seq[(Seq[(os.Path, String)], Artifact)] = T.sequence(publishArtifacts.value)().map {
      case PublishModule.PublishData(a, s) => (s.map { case (p, f) => (p.path, f) }, a)
    }

    new GitlabPublisher(
        repo,
        auth,
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

trait GitlabMavenRepository {

  def gitlabEnv: GitlabEnvironment = new GitlabEnvironment {} // For token discovery
  def repository: GitlabPackageRepository // For package discovery

  def mavenRepo(): MavenRepository = {
    val gitlabAuth = GitlabToken.resolveGitlabToken(gitlabEnv).get
    val auth       = Authentication(gitlabAuth.headers)
    MavenRepository(repository.url(), Some(auth))
  }
}
