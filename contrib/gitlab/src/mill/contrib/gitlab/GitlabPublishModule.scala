package mill.contrib.gitlab

import coursier.core.Authentication
import coursier.maven.MavenRepository
import mill._
import mill.api.{Ctx, Logger}
import mill.define.{Command, ExternalModule, Input}
import mill.scalalib.publish.Artifact
import scalalib._

class GitlabAuthenticationException(message: String) extends Exception(message)

trait GitlabPublishModule extends PublishModule {

  def publishRepository: ProjectRepository

  def skipPublish: Boolean = false

  def tokenLookup: GitlabTokenLookup = new GitlabTokenLookup {}

  def gitlabHeaders(
      log: Logger,
      env: Map[String, String],
      props: Map[String, String]
  ): GitlabAuthHeaders = {
    val auth = tokenLookup.resolveGitlabToken(log, env, props)
    if (auth.isEmpty) {
      throw new GitlabAuthenticationException(
        s"Unable to resolve authentication with $tokenLookup"
      )
    }
    auth.get
  }

  def env: Input[Map[String, String]] = T.input(T.ctx().env)
  def props: Map[String, String] = sys.props.toMap

  def publishGitlab(
      readTimeout: Int = 60000,
      connectTimeout: Int = 5000
  ): define.Command[Unit] = T.command {

    val gitlabRepo = publishRepository
    val gitlabAuth = gitlabHeaders(T.log, env(), props)

    val PublishModule.PublishData(artifactInfo, artifacts) = publishArtifacts()
    if (skipPublish) {
      T.log.info(s"SkipPublish = true, skipping publishing of $artifactInfo")
    } else {
      val uploader = new GitlabUploader(gitlabAuth)
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

trait GitlabMavenRepository {
  def tokenLookup: GitlabTokenLookup = new GitlabTokenLookup {} // For token discovery
  def repository: GitlabPackageRepository // For package discovery

  def mavenRepo(implicit context: Ctx): MavenRepository = {
    val maybeRepository = tokenLookup
      .resolveGitlabToken(context.log, context.env, sys.props.toMap)
      .map(gitlabAuth => Authentication(gitlabAuth.headers))
      .map(auth => MavenRepository(repository.url(), Some(auth)))

    maybeRepository.getOrElse {
      val indent = "      "
      val tokenSearchPlaces = tokenLookup.tokenSearchOrder.mkString("", s",\n$indent", "\n")
      val error =
        s"Could not find Gitlab authentication for $repository, \n  Searched from:\n$indent$tokenSearchPlaces"
      context.log.error(error)
      throw new RuntimeException()
    }
  }
}
