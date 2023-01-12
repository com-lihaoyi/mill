package mill.contrib.gitlab

import coursier.core.Authentication
import coursier.maven.MavenRepository
import mill.T
import mill.api.Result
import mill.api.Result.{Failure, Success}
import mill.define.Task
import mill.scalalib.ScalaModule

trait GitlabMavenRepository extends ScalaModule {

  def tokenLookup: GitlabTokenLookup = new GitlabTokenLookup {} // For token discovery
  def gitlabRepository: GitlabPackageRepository // For package discovery

//  def mavenRepository: Task[MavenRepository] = T.task {
//
//    val gitlabAuth = tokenLookup.resolveGitlabToken(T.env.get, sys.props.get)()
//      .map(auth => Authentication(auth.headers))
//      .map(auth => MavenRepository(gitlabRepository.url(), Some(auth)))
//
//    gitlabAuth match {
//      case Left(msg) =>
//        Failure(
//          s"Token lookup for PACKAGE repository ($gitlabRepository) failed with $msg"
//        ): Result[MavenRepository]
//      case Right(value) => Success(value)
//    }
//  }
}
