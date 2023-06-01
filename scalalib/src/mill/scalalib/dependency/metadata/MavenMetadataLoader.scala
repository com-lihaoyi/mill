package mill.scalalib.dependency.metadata

import coursier.maven.MavenRepository
import coursier.util.Task
import mill.scalalib.dependency.versions.Version

private[dependency] final case class MavenMetadataLoader(mavenRepo: MavenRepository)
    extends MetadataLoader {

  private val fetch = coursier.cache.FileCache[Task]().fetch

  override def getVersions(module: coursier.Module): List[Version] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    // TODO fallback to 'versionsFromListing' if 'versions' doesn't work? (needs to be made public in coursier first)
    val allVersions = mavenRepo.versions(module, fetch).run.unsafeRun()
    allVersions
      .map(_._1.available.map(Version(_)))
      .getOrElse(List.empty)
  }
}
