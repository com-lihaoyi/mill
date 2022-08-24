package mill.contrib.gitlab

import scala.util.Try

case class GitlabToken(headers: Seq[(String, String)])

object GitlabToken {
  def apply(header: String, value: String): GitlabToken = GitlabToken(Seq(header -> value))

  def personalToken(token: String): GitlabToken = GitlabToken("Private-Token", token)
  def deployToken(token: String): GitlabToken   = GitlabToken("Deploy-Token", token)
  def jobToken(token: String): GitlabToken      = GitlabToken("Job-Token", token)

  def resolveGitlabToken(env: GitlabEnvironment): Option[GitlabToken] = {
    env.tokenSearchOrder.collectFirst { tokenSource =>
      tokenSource.authentication match {
        case Right(value) => value
      }
    }
  }
}

trait GitlabEnvironment {
  import GitlabEnvironment._
  import GitlabToken._

  def personalTokenFile: os.Path = os.home / os.RelPath(".mill/gitlab/personal-access-token")
  def personalTokenEnv: String   = "GITLAB_PERSONAL_ACCESS_TOKEN"
  def deployTokenFile: os.Path   = os.home / os.RelPath(".mill/gitlab/deploy-token")
  def deployTokenEnv: String     = "GITLAB_DEPLOY_TOKEN"
  def jobTokenEnv: String        = "CI_JOB_TOKEN"

  def tokenSearchOrder: Seq[TokenSource] = Seq(
      Env(personalTokenEnv, personalToken),
      File(personalTokenFile, personalToken),
      Env(deployTokenEnv, deployToken),
      File(deployTokenFile, deployToken),
      Env(jobTokenEnv, jobToken)
  )
}

object GitlabEnvironment {
  sealed trait TokenSource {
    def authentication: Either[String, GitlabToken]
  }

  case class Env(name: String, f: String => GitlabToken) extends TokenSource {
    def authentication: Either[String, GitlabToken] =
      sys.env.get(name).map(f).toRight(s"Could not read environment variable $name")
  }

  case class File(path: os.Path, f: String => GitlabToken) extends TokenSource {
    def authentication: Either[String, GitlabToken] =
      Try(os.read(path))
        .map(_.trim) // At least \n at the end is usually present
        .map(f)
        .toEither
        .left
        .map(_.toString)
  }

  case class Custom(f: () => Either[String, GitlabToken]) extends TokenSource {
    override def authentication: Either[String, GitlabToken] = f()
  }

}
