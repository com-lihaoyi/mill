package mill.javalib.quarkus

import mill.api.daemon.experimental
import upickle.ReadWriter
import mill.api.JsonFormatters.pathReadWrite

@experimental
trait ApplicationModelWorker extends AutoCloseable {
  def quarkusBootstrapApplication(
      applicationModelFile: os.Path,
      destRunJar: os.Path,
      jar: os.Path
  ): os.Path

  def quarkusGenerateApplicationModel(
      appModel: ApplicationModelWorker.AppModel,
      destination: os.Path
  ): os.Path

  def quarkusDeploymentDependencies(runtimeDeps: Seq[ApplicationModelWorker.Dependency])
      : Seq[ApplicationModelWorker.Dependency]

}

object ApplicationModelWorker {

  /**
   * This app model has the necessary
   * elements to build the Quarkus Application Model. This data
   * class is used to populate the quarkus `ApplicationModelBuilder` and `PlatformInfo`
   * which are serialized for the QuarkusBootstrap to be able to create the Quarkus build artifacts.
   *
   * The effort for Quarkus support is ongoing.
   *
   * For details on the requirements see [[https://github.com/quarkusio/quarkus/tree/main/independent-projects/bootstrap/app-model/src/main/java/io/quarkus/bootstrap/model]]
   */
  case class AppModel(
      projectRoot: os.Path,
      buildDir: os.Path,
      buildFile: os.Path,
      quarkusVersion: String,
      groupId: String,
      artifactId: String,
      version: String,
      moduleData: Seq[ModuleData],
      boms: Seq[String],
      dependencies: Seq[Dependency],
      nativeImage: String,
      appMode: AppMode
  ) derives ReadWriter

  case class Dependency(
      groupId: String,
      artifactId: String,
      version: String,
      resolvedPath: os.Path,
      isRuntime: Boolean,
      isDeployment: Boolean,
      isTopLevelArtifact: Boolean,
      hasExtension: Boolean
  ) derives ReadWriter

  case class Source(dir: os.Path, destDir: os.Path) derives ReadWriter

  case class ModuleData(classifier: ModuleClassifier, sources: Source, resources: Source)
      derives ReadWriter

  enum AppMode derives ReadWriter {
    case App
    case Test
  }

  enum ModuleClassifier derives ReadWriter {
    case Main
    case Tests
  }
}
