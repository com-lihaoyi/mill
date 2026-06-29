package mill.javalib.quarkus

import mill.api.daemon.experimental
import upickle.ReadWriter
import mill.api.JsonFormatters.pathReadWrite
import mill.api.PathRef
import mill.javalib.quarkus.ApplicationModelWorker.LaunchMode

@experimental
trait ApplicationModelWorker extends AutoCloseable {
  def quarkusBootstrapApplication(
      applicationModelFile: os.Path,
      destRunJar: os.Path,
      jar: os.Path,
      buildProperties: os.Path
  ): ApplicationModelWorker.QuarkusApp

  def quarkusGenerateApplicationModel(
      appModel: ApplicationModelWorker.AppModel,
      destination: os.Path
  ): os.Path

  def quarkusDeploymentDependencies(runtimeDeps: Seq[ApplicationModelWorker.Dependency])
      : Seq[ApplicationModelWorker.Dependency]

  def quarkusCodeGen(
      appModel: ApplicationModelWorker.AppModel,
      generatedSourcesDir: os.Path,
      sourcesDir: Seq[os.Path],
      buildDir: os.Path,
      buildProperties: os.Path,
      launchMode: LaunchMode,
      isTest: Boolean
  ): os.Path

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

  /** Mill API for io.quarkus.runtime.LaunchMode. */
  enum LaunchMode derives ReadWriter {

    /** Normal production build (Native Image or JVM) */
    case Normal

    /** Like Normal but with dev services supported ([[https://quarkus.io/guides/dev-services]]]. Doesn't make a difference in mill yet */
    case Run

    /** Like run but also with live reload (dev mode). Doesn't make a difference in mill yet */
    case Development

    /** A test run */
    case Test
  }

  enum ModuleClassifier derives ReadWriter {
    case Main
    case Tests
    case NativeTests
  }

  case class QuarkusApp(buildOutput: PathRef, runJar: Option[PathRef], nativePath: Option[PathRef])
      derives ReadWriter

  object QuarkusApp {

    def apply(
        buildOutput: os.Path,
        runJar: Option[os.Path],
        nativePath: Option[os.Path]
    ): QuarkusApp =
      QuarkusApp(PathRef(buildOutput), runJar.map(PathRef(_)), nativePath.map(PathRef(_)))

  }
}
