package mill.contrib.gitlab

import mill.T
import mill.T._
import mill.api.Result
import mill.api.Result.Failure
import mill.api.Result.Success
import mill.define.Ctx.make
import mill.define.Task

trait GitlabTokenLookup {
  import GitlabTokenLookup._

  // Default search places for token
  def personalTokenEnv: Task[String] = T.task("GITLAB_PERSONAL_ACCESS_TOKEN")
  def personalTokenProperty: Task[String] = T.task("gitlab.personal-access-token")
  def personalTokenFile: Task[os.Path] =
    T.task(os.home / os.RelPath(".mill/gitlab/personal-access-token"))
  def personalTokenFileWD: Task[os.Path] =
    T.task(T.workspace / os.RelPath(".gitlab/personal-access-token"))
  def deployTokenEnv: Task[String] = T.task("GITLAB_DEPLOY_TOKEN")
  def deployTokenProperty: Task[String] = T.task("gitlab.deploy-token")
  def deployTokenFile: Task[os.Path] = T.task(os.home / os.RelPath(".mill/gitlab/deploy-token"))
  def deployTokenFileWD: Task[os.Path] = T.task(T.workspace / os.RelPath(".gitlab/deploy-token"))
  def jobTokenEnv: Task[String] = T.task("CI_JOB_TOKEN")

  // Default token search order. Implementation picks first found and does not look for the rest.
  def tokenSearchOrder: Task[Seq[GitlabToken]] = T.task {
    Seq(
      Personal(Env(personalTokenEnv())),
      Personal(Property(personalTokenProperty())),
      Personal(File(personalTokenFile())),
      Personal(File(personalTokenFileWD())),
      Deploy(Env(deployTokenEnv())),
      Deploy(Property(deployTokenProperty())),
      Deploy(File(deployTokenFile())),
      Deploy(File(deployTokenFileWD())),
      CIJob(Env(jobTokenEnv()))
    )
  }

  // Finds gitlab token from this environment. Overriding this is not generally necessary.
  def resolveGitlabToken(
      env: Map[String, String],
      // env: String => Option[String],
      // env: Task[String => Option[String]],
      prop: String => Option[String]
      // ): Task[GitlabAuthHeaders] = T.task {
  )(implicit ctx: mill.api.Ctx): Task[GitlabAuthHeaders] = T.task {

    val searchFrom = tokenSearchOrder()

    val token = LazyList
      .from(searchFrom)
      .map(token => buildHeaders(token, env, prop))
      // .tapEach(e => e.left.foreach(msg => T.log.debug(msg)))
      // .find(_.isRight)
      .head
      .evaluate(ctx)
    // .flatMap(_.toOption)

    token
//    token match {
//      case None => Failure(s"Unable to find token from $searchFrom")
//      case Some(headers) => Success(headers)
//    }
  }

  // Converts GitlabToken to GitlabAuthHeaders. Overriding this is not generally necessary.
  def buildHeaders(
      token: GitlabToken,
      // env: String => Option[String],
      env: Map[String, String],
      prop: String => Option[String]
      // ): Either[String, GitlabAuthHeaders] = {
  )(implicit ctx: mill.api.Ctx): Task[GitlabAuthHeaders] = T.task {

    def readSource(source: TokenSource): Result[String] = Result.create {
      source match {
        case Env(name) => env.get(name).get
        // .map(Success(_)).getOrElse(Failure(s"Could not read environment variable $name"))
        case Property(property) => prop(property).get
        // prop(property).toRight(s"Could not read system property variable $prop")
        case File(path) => os.read(path).trim
        // Try(os.read(path)).map(_.trim).toEither.left.map(e => s"failed to read file $e")
        case Custom(f) => f().toOption.get
      }
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
   * Possible additions, that can now be supported with Custom: KeyVault, Yaml, etc..
   */
  sealed trait TokenSource
  case class Env(name: String) extends TokenSource
  case class File(path: os.Path) extends TokenSource
  case class Property(property: String) extends TokenSource
  case class Custom(f: () => Either[String, String]) extends TokenSource
}
