package mill.spotless

import com.diffplug.spotless.Provisioner
import coursier.*
import coursier.parse.DependencyParser
import mill.util.Jvm

import java.io.File
import java.util
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.jdk.CollectionConverters.*

class CoursierProvisioner(
    parseMvnCoordinates: String => Dependency = CoursierProvisioner.parseMvnCoordinates,
    repositories: Seq[Repository] = CoursierProvisioner.resolveRepositories,
    artifactTypes: Option[Set[Type]] = Some(Resolution.defaultTypes)
) extends Provisioner {

  def provisionWithTransitives(
      withTransitives: Boolean,
      mavenCoordinates: util.Collection[String]
  ): util.Set[File] = {
    val deps = mavenCoordinates.asScala.map(parseMvnCoordinates)
    val arts = Jvm.getArtifacts(repositories, deps, artifactTypes = artifactTypes).get
    util.Set.of(arts.files*)
  }
}
object CoursierProvisioner {

  private def parseMvnCoordinates(s: String): Dependency = {
    DependencyParser.dependency(s, "") match {
      case Left(msg) => throw new Exception(msg)
      case Right(dep) => dep
    }
  }

  // copied from mill.scalalib.CoursierModule.repositoriesTask
  private def resolveRepositories: Seq[Repository] = {
    val resolve = Resolve()
    Await.result(resolve.finalRepositories.future()(resolve.cache.ec), Duration.Inf)
  }
}
