package mill.contrib.gitlab

import coursier.core.Authentication
import coursier.maven.MavenRepository
import mill.{Task, T}
import mill.api.Result
import mill.api.Result.{Failure, Success}

trait GitlabMavenRepository {

  def tokenLookup: GitlabTokenLookup = new GitlabTokenLookup {} // For token discovery
  def gitlabRepository: GitlabPackageRepository // For package discovery

  def mavenRepository: Task[MavenRepository] = Task.anon {

    val gitlabAuth = tokenLookup.resolveGitlabToken(Task.env, sys.props.toMap, Task.workspace)
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
