package mill.scalalib.dependency.metadata

import coursier.Fetch.Content
import coursier.core.{Artifact, Module, Project, Repository}
import coursier.ivy.IvyRepository
import coursier.maven.MavenRepository
import scalaz.{EitherT, Monad}
import utest._

object MetadataLoaderFactoryTests extends TestSuite {

  val tests = Tests {
    'mavenRepository - {
      val mavenRepo = MavenRepository("https://repo1.maven.org/maven2")
      assertMatch(MetadataLoaderFactory(mavenRepo)) {
        case Some(MavenMetadataLoader(`mavenRepo`)) =>
      }
    }
    'ivyRepository - {
      val ivyRepo = IvyRepository(
        "https://dl.bintray.com/sbt/sbt-plugin-releases/" + coursier.ivy.Pattern.default.string,
        dropInfoAttributes = true)
      assertMatch(MetadataLoaderFactory(ivyRepo)) { case None => }
    }
    'otherRepository - {
      val otherRepo = new CustomRepository
      assertMatch(MetadataLoaderFactory(otherRepo)) { case None => }
    }
  }

  case class CustomRepository() extends Repository {
    override def find[F[_]](module: Module, version: String, fetch: Content[F])(
        implicit F: Monad[F]): EitherT[F, String, (Artifact.Source, Project)] =
      ???
  }
}
