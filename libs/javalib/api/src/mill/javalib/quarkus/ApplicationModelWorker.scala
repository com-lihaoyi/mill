package mill.javalib.quarkus

trait ApplicationModelWorker extends AutoCloseable {
  def bootstrapQuarkus(
      projectRoot: os.Path,
      organization: String,
      artifactName: String,
      artifactVersion: String,
      jar: os.Path,
      destination: os.Path
  ): Unit
}
