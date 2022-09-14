package mill.contrib.gitlab

import mill.api.Logger

import scala.util.Try

trait GitlabTokenLookup {
  import GitlabTokenLookup._

  // Default search places for token
  def personalTokenEnv: String      = "GITLAB_PERSONAL_ACCESS_TOKEN"
  def personalTokenProperty: String = "gitlab.personal-access-token"
  def personalTokenFile: os.Path    = os.home / os.RelPath(".mill/gitlab/personal-access-token")
  def personalTokenFileWD: os.Path  = os.home / os.RelPath(".gitlab/personal-access-token")
  def deployTokenEnv: String        = "GITLAB_DEPLOY_TOKEN"
  def deployTokenProperty: String   = "gitlab.deploy-token"
  def deployTokenFile: os.Path      = os.home / os.RelPath(".mill/gitlab/deploy-token")
  def deployTokenFileWD: os.Path    = os.pwd / os.RelPath(".gitlab/deploy-token")
  def jobTokenEnv: String           = "CI_JOB_TOKEN"

  // Default token search order. Implementation picks first found and does not look for the rest.
  def tokenSearchOrder: Seq[GitlabToken] = Seq(
      Personal(Env(personalTokenEnv)),
      Personal(Property(personalTokenProperty)),
      Personal(File(personalTokenFile)),
      Personal(File(personalTokenFileWD)),
      Deploy(Env(deployTokenEnv)),
      Deploy(Property(deployTokenProperty)),
      Deploy(File(deployTokenFile)),
      Deploy(File(deployTokenFileWD)),
      CIJob(Env(jobTokenEnv))
  )

  // Finds gitlab token from this environment. Overriding this is not generally necessary.
  def resolveGitlabToken(
      log: Logger,
      env: Map[String, String],
      props: Map[String, String]
  ): Option[GitlabAuthHeaders] = {
    LazyList
      .from(tokenSearchOrder)
      .map(tt => buildHeaders(tt, env, props))
      .tapEach(e => e.left.foreach(msg => log.debug(msg)))
      .find(_.isRight)
      .flatMap(_.toOption)
  }

  // Converts GitlabToken to GitlabAuthHeaders. Overriding this is not generally necessary.
  def buildHeaders(
      token: GitlabToken,
      env: Map[String, String],
      props: Map[String, String]
  ): Either[String, GitlabAuthHeaders] = {

    def readSource(source: TokenSource): Either[String, String] = source match {
      case Env(name)      => env.get(name).toRight(s"Could not read environment variable $name")
      case Property(prop) => props.get(prop).toRight(s"Could not read system property variable $prop")
      case File(path)     => Try(os.read(path)).map(_.trim).toEither.left.map(e => s"failed to read file $e")
      case Custom(f)      => f()
    }

    token match {
      case Personal(source)             => readSource(source).map(GitlabAuthHeaders.privateToken)
      case Deploy(source)               => readSource(source).map(GitlabAuthHeaders.deployToken)
      case CIJob(source)                => readSource(source).map(GitlabAuthHeaders.jobToken)
      case CustomHeader(header, source) => readSource(source).map(GitlabAuthHeaders(header, _))
    }
  }

  override def toString(): String =
    s"GitlabEnvironment looking token from ${tokenSearchOrder.mkString(", ")}"
}

object GitlabTokenLookup {

  /** Possible types of a Gitlab authentication header.
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
  case class Personal(source: TokenSource)                     extends GitlabToken
  case class Deploy(source: TokenSource)                       extends GitlabToken
  case class CIJob(source: TokenSource)                        extends GitlabToken
  case class CustomHeader(header: String, source: TokenSource) extends GitlabToken

  /** Possible source of token value. Either an
    *   - Env = Environment variable
    *   - Property = Javas system property
    *   - File =Contents of a file on local disk.
    *   - Custom = Own function
    *
    * Possible additions, that can now be supported with Custom: KeyVault, Yaml, etc..
    */
  sealed trait TokenSource
  case class Env(name: String)                       extends TokenSource
  case class File(path: os.Path)                     extends TokenSource
  case class Property(property: String)              extends TokenSource
  case class Custom(f: () => Either[String, String]) extends TokenSource
}
