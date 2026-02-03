package mill.javalib.quarkus

import io.quarkus.bootstrap.BootstrapAppModelFactory
import io.quarkus.bootstrap.app.ApplicationModelSerializer
import io.quarkus.bootstrap.util.BootstrapUtils
import io.quarkus.maven.dependency.{ArtifactCoords, ResolvedArtifactDependency}
import io.quarkus.paths.PathList

class ApplicationModelWorkerImpl extends ApplicationModelWorker {

  override def bootstrapQuarkus(
      projectRoot: os.Path,
      organization: String,
      artifactName: String,
      artifactVersion: String,
      jar: os.Path,
      destination: os.Path
  ): Unit = {
    val factory = BootstrapAppModelFactory.newInstance()
    factory.setProjectRoot(projectRoot.toNIO)
    val appArtifact = ResolvedArtifactDependency(
      ArtifactCoords.jar(
        organization,
        artifactName,
        artifactVersion
      ),
      PathList.of(jar.toNIO)
    )

    factory.setAppArtifact(appArtifact)

    val appModel = factory.resolveAppModel().getApplicationModel

    ApplicationModelSerializer.serialize(
      appModel,
      BootstrapUtils.resolveSerializedAppModelPath(destination.toNIO)
    )

  }

  override def close(): Unit = {
    // no op
  }
}
