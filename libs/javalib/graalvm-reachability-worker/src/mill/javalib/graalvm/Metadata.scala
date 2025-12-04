package mill.javalib.graalvm

import org.graalvm.reachability.Query
import org.graalvm.reachability.internal.FileSystemRepository

import java.util.function.Consumer
import scala.jdk.CollectionConverters.*


object Metadata {

  def findConfigurations(rootDirectory: os.Path, deps: Set[String]) = {
    val repository: FileSystemRepository = new FileSystemRepository(rootDirectory.toNIO)
    val queryBuilder: Consumer[Query] = (q: Query) => {
      q.forArtifacts(deps.toSeq*)
      q.useLatestConfigWhenVersionIsUntested()
    }
    repository.findConfigurationsFor(queryBuilder).asScala.toSet.map(
      _.getDirectory
    ).map(p => os.Path(p.toString))

  }
}
