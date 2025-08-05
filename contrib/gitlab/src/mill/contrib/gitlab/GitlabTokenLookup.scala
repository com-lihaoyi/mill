package mill.contrib.gitlab

import scala.util.Try
import mill.api.Result
trait GitlabTokenLookup {
  import GitlabTokenLookup._

  // Default search places for token
  def personalTokenEnv: String = "GITLAB_PERSONAL_ACCESS_TOKEN"
  def personalTokenProperty: String = "gitlab.personal-access-token"
  def personalTokenFile: os.Path = os.home / os.RelPath(".mill/gitlab/personal-access-token")
  def personalTokenFileWD: os.RelPath = os.RelPath(".gitlab/personal-access-token")
  def deployTokenEnv: String = "GITLAB_DEPLOY_TOKEN"
  def deployTokenProperty: String = "gitlab.deploy-token"
  def deployTokenFile: os.Path = os.home / os.RelPath(".mill/gitlab/deploy-token")
  def deployTokenFileWD: os.RelPath = os.RelPath(".gitlab/deploy-token")
  def jobTokenEnv: String = "CI_JOB_TOKEN"

  // Default token search order. Implementation picks first found and does not look for the rest.
  def tokenSearchOrder: Seq[GitlabToken] = Seq(
    Personal(Env(personalTokenEnv)),
    Personal(Property(personalTokenProperty)),
    Personal(File(personalTokenFile)),
    Personal(WorkspaceFile(personalTokenFileWD)),
    Deploy(Env(deployTokenEnv)),
    Deploy(Property(deployTokenProperty)),
    Deploy(File(deployTokenFile)),
    Deploy(WorkspaceFile(deployTokenFileWD)),
    CIJob(Env(jobTokenEnv))
  )

  // Finds gitlab token from this environment. Overriding this is not generally necessary.
  def resolveGitlabToken(
      env: Map[String, String],
      prop: Map[String, String],
      workspace: os.Path
  ): Result[GitlabAuthHeaders] = {

    val token = LazyList
      .from(tokenSearchOrder)
      .map(token => buildHeaders(token, env, prop, workspace))
      .find(_.isInstanceOf[Result.Success[?]])
      .flatMap(_.toOption)

    token match {
      case None =>
        Result.Failure(s"Unable to find token from $tokenSearchOrder")
      case Some(headers) => Result.Success(headers)
    }
  }

  // Converts GitlabToken to GitlabAuthHeaders. Overriding this is not generally necessary.
  def buildHeaders(
      token: GitlabToken,
      env: Map[String, String],
      prop: Map[String, String],
      workspace: os.Path
  ): Result[GitlabAuthHeaders] = {

    def readPath(path: os.Path): Result[String] =
      Result.fromEither(Try(os.read(path)).map(_.trim).toEither.left.map(e =>
        s"failed to read file $e"
      ))

    def readSource(source: TokenSource): Result[String] =
      source match {
        case Env(name) =>
          Result.fromEither(env.get(name).toRight(s"Could not read environment variable $name"))
        case Property(property) =>
          Result.fromEither(
            prop.get(property).toRight(s"Could not read system property variable $prop")
          )
        case File(path) =>
          readPath(path)
        case WorkspaceFile(path) =>
          readPath(path.resolveFrom(workspace))
        case Custom(f) => f()
      }

    token match {
      case Personal(source) => readSource(source).map(GitlabAuthHeaders.privateToken)
      case Deploy(source) => readSource(source).map(GitlabAuthHeaders.deployToken)
      case CIJob(source) => readSource(source).map(GitlabAuthHeaders.jobToken)
      case CustomHeader(header, source) => readSource(source).map(GitlabAuthHeaders(header, _))
    }
  }

}

object GitlabTokenLookup {

  /**
   * Possible types of a Gitlab authentication header.
   *   - Personal = "Private-Token" ->
   *   - Deploy = "Deploy-Token"->
   *   - CIJob = "Job-Token" ->
   *   - CustomHeader = Use with TokenSource/Custom to produce anything you like
   *
   * Currently only one custom header is supported. If you need multiple override gitlabToken from GitlabPublishModule
   * directly
   */
  trait GitlabToken {
    def source: TokenSource
  }
  case class Personal(source: TokenSource) extends GitlabToken
  case class Deploy(source: TokenSource) extends GitlabToken
  case class CIJob(source: TokenSource) extends GitlabToken
  case class CustomHeader(header: String, source: TokenSource) extends GitlabToken

  /**
   * Possible source of token value. Either an
   *   - Env = Environment variable
   *   - Property = Javas system property
   *   - File =Contents of a file on local disk.
   *   - Custom = Own function
   *
   * Possible additions, that can now be supported with Custom: KeyVault, Yaml, etc.
   */
  sealed trait TokenSource
  case class Env(name: String) extends TokenSource
  case class File(path: os.Path) extends TokenSource
  case class WorkspaceFile(path: os.RelPath) extends TokenSource
  case class Property(property: String) extends TokenSource
  case class Custom(f: () => Result[String]) extends TokenSource
}
