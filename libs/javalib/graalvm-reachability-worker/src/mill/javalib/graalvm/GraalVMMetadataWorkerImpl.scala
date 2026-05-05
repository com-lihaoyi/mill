package mill.javalib.graalvm

import org.graalvm.reachability.{DirectoryConfiguration, Query}
import org.graalvm.reachability.internal.FileSystemRepository

import java.util.function.Consumer
import scala.jdk.CollectionConverters.*

/**
 * Uses [[org.graalvm.reachability.internal.FileSystemRepository]] to discover
 * reachability metadata from the graalvm-reachability-metadata repository.
 *
 * For more information go to [[https://github.com/oracle/graalvm-reachability-metadata]] and [[https://github.com/graalvm/native-build-tools]]
 */
class GraalVMMetadataWorkerImpl extends GraalVMMetadataWorker {

  override def reachabilityMetadataVersion: String = Versions.graalVmReachabilityMetadata

  override def downloadRepo(workDir: os.Path, version: String): os.Path = {
    val downloadedMetadata = workDir / "graalvm-reachability-metadata.zip"

    val rootDir = workDir / "graalvm-reachability-metadata"

    os.write(
      downloadedMetadata,
      requests.get(
        s"https://github.com/oracle/graalvm-reachability-metadata/releases/download/$version/graalvm-reachability-metadata-$version.zip"
      )
    )

    os.unzip(downloadedMetadata, rootDir)

    rootDir
  }

  override def findConfigurations(metadataQuery: MetadataQuery): Set[MetadataResult] = {
    val repository: FileSystemRepository = new FileSystemRepository(metadataQuery.rootPath.toNIO)
    val queryBuilder: Consumer[Query] = (q: Query) => {
      q.forArtifacts(metadataQuery.deps.toSeq*)
      if (metadataQuery.useLatestConfigWhenVersionIsUntested) {
        q.useLatestConfigWhenVersionIsUntested()
      }
    }
    repository.findConfigurationsFor(queryBuilder).asScala.toSet.map(d =>
      MetadataResult(
        dependencyGroupId = d.getGroupId,
        dependencyArtifactId = d.getArtifactId,
        dependencyVersion = d.getVersion,
        metadataLocation = os.Path(d.getDirectory),
        isOverride = d.isOverride
      )
    )
  }

  override def copyDirectoryConfiguration(
      metadataResults: Set[MetadataResult],
      destination: os.Path
  ): Unit = {
    DirectoryConfiguration.copy(
      metadataResults.map(mr =>
        new DirectoryConfiguration(
          mr.dependencyGroupId,
          mr.dependencyArtifactId,
          mr.dependencyVersion,
          mr.metadataLocation.toNIO,
          mr.isOverride
        )
      ).asJavaCollection,
      destination.toNIO
    )
  }

  override def close(): Unit = {
    // no op
  }
}
