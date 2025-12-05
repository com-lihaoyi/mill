package mill.javalib.graalvm

import org.graalvm.reachability.Query
import org.graalvm.reachability.internal.FileSystemRepository

import java.util.function.Consumer
import scala.jdk.CollectionConverters.*

class GraalVMMetadataWorkerImpl extends GraalVMMetadataWorker {

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

  override def findConfigurations(metadataQuery: MetadataQuery): Set[os.Path] = {
    val repository: FileSystemRepository = new FileSystemRepository(metadataQuery.rootPath.toNIO)
    val queryBuilder: Consumer[Query] = (q: Query) => {
      q.forArtifacts(metadataQuery.deps.toSeq*)
      if (metadataQuery.useLatestConfigWhenVersionIsUntested) {
        q.useLatestConfigWhenVersionIsUntested()
      }
    }
    repository.findConfigurationsFor(queryBuilder).asScala.toSet.map(
      _.getDirectory
    ).map(p => os.Path(p.toString))
  }
}
