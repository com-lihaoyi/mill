package mill.scalalib.dependency.metadata

import coursier.Repository
import coursier.maven.MavenRepository

private[dependency] object MetadataLoaderFactory {
  def apply(repo: Repository): Option[MetadataLoader] = repo match {
    case mavenRepo: MavenRepository => Some(MavenMetadataLoader(mavenRepo))
    case _ => None
  }
}
