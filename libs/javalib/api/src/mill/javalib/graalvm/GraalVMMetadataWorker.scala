package mill.javalib.graalvm

trait GraalVMMetadataWorker extends AutoCloseable {
  def reachabilityMetadataVersion: String
  def downloadRepo(workDir: os.Path, version: String): os.Path
  def findConfigurations(metadataQuery: MetadataQuery): Set[os.Path]
}
