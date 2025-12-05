package mill.javalib.graalvm

@mill.api.experimental
trait GraalVMMetadataWorker extends AutoCloseable {
  def reachabilityMetadataVersion: String
  def downloadRepo(workDir: os.Path, version: String): os.Path
  def findConfigurations(metadataQuery: MetadataQuery): Set[MetadataResult]
  def copyDirectoryConfiguration(metadataResults: Set[MetadataResult], destination: os.Path): Unit
}
