package mill.javalib.quarkus

trait ApplicationModelWorker extends AutoCloseable {
  def bootstrapQuarkus(
      appModel: ApplicationModelWorker.AppModel,
      destination: os.Path
  ): Unit
}

object ApplicationModelWorker {
  case class AppModel(
      projectRoot: os.Path,
      groupId: String,
      artifactId: String,
      version: String,
      sourcesDir: os.Path,
      resourcesDir: os.Path,
      compiledPath: os.Path,
      compiledResources: os.Path,
      boms: Seq[String],
      dependencies: Seq[Dependency]
  )

  case class Dependency(groupId: String, artifactId: String, version: String, resolvedPath: os.Path)
}
