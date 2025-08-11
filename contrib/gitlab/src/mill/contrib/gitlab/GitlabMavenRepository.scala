package mill.contrib.gitlab

import coursier.core.Authentication
import coursier.maven.MavenRepository
import mill.api.Result
import mill.api.Result.{Failure, Success}
import mill.api.Task
import mill.api.BuildCtx

trait GitlabMavenRepository {

  def tokenLookup: GitlabTokenLookup = new GitlabTokenLookup {} // For token discovery
  def gitlabRepository: GitlabPackageRepository // For package discovery

  def mavenRepository: Task[MavenRepository] = Task.Anon {

    val gitlabAuth =
      tokenLookup.resolveGitlabToken(Task.env, sys.props.toMap, BuildCtx.workspaceRoot)
        .map(auth => Authentication(auth.headers))
        .map(auth => MavenRepository(gitlabRepository.url(), Some(auth)))

    gitlabAuth match {
      case Result.Failure(msg) =>
        Task.fail(s"Token lookup for PACKAGE repository ($gitlabRepository) failed with $msg")
      case Result.Success(value) => value
    }
  }
}
