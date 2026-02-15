package mill.javalib.quarkus

import io.quarkus.bootstrap.app.{ApplicationModelSerializer, AugmentAction, QuarkusBootstrap}
import io.quarkus.bootstrap.model.{
  ApplicationModelBuilder,
  CapabilityContract,
  PlatformImportsImpl,
  PlatformReleaseInfo
}
import io.quarkus.bootstrap.util.BootstrapUtils
import io.quarkus.bootstrap.workspace.{
  ArtifactSources,
  SourceDir,
  WorkspaceModule,
  WorkspaceModuleId
}
import io.quarkus.bootstrap.{BootstrapAppModelFactory, BootstrapConstants}
import io.quarkus.fs.util.ZipUtils
import io.quarkus.maven.dependency.{ArtifactCoords, DependencyFlags, ResolvedDependencyBuilder}
import io.quarkus.paths.PathList
import mill.javalib.quarkus.ApplicationModelWorker.AppMode.Test
import mill.javalib.quarkus.ApplicationModelWorker.{AppMode, ModuleClassifier, ModuleData}

import java.nio.file.Files
import java.util.Properties
import scala.jdk.CollectionConverters
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * The ApplicationModelWorker is mill's integration against Quarkus bootstrapping.
 *
 * At the moment there are 2 stages supported:
 * 1. Create a single module's Application Model and serialize it
 * 2. Feed the deserialised model from step 1 to [[QuarkusBootstrap]] and execute
 * the Quarkus build with an AugmentAction
 *
 * In addition, a helper method supports picking in the library or module dependencies
 * to detect which have deployment dependencies.
 *
 * For more information on that see [[https://quarkus.io/guides/conditional-extension-dependencies]]
 */
class ApplicationModelWorkerImpl extends ApplicationModelWorker {

  /**
   * Bootstraps a Quarkus Application with the application model
   * provided (usually created by [[quarkusGenerateApplicationModel]]).
   * Needs a target directory and a compiled artifact (jar)
   * @param applicationModelFile The path of the serialized application model file
   * @param destRunJar The directory to store the resulting `quarkus-run.jar`
   * @param jar The jar created by the Mill JavaModule (outside of Quarkus)
   * @return The path of the resulting `quarkus-run.jar`
   */
  def quarkusBootstrapApplication(
      applicationModelFile: os.Path,
      destRunJar: os.Path,
      jar: os.Path
  ): os.Path = {
    val applicationModel = ApplicationModelSerializer
      .deserialize(applicationModelFile.toNIO)

    val quarkusBootstrap = QuarkusBootstrap.builder()
      .setExistingModel(applicationModel)
      .setAppArtifact(
        ResolvedDependencyBuilder.newInstance()
          .setResolvedPath(jar.toNIO)
      )
      .setTargetDirectory(destRunJar.toNIO)
      .setLocalProjectDiscovery(false)
      .setBaseClassLoader(getClass.getClassLoader)
      .build()

    val augmentAction: AugmentAction = quarkusBootstrap.bootstrap().createAugmentor()

    os.Path(augmentAction.createProductionApplication().getJar.getPath)
  }

  /**
   * A helper method for any Mill [[QuarkusModule]] that detects which dependencies are runtime
   * extension artifacts and thus have a deployment equivalent as specified in [[https://quarkus.io/guides/conditional-extension-dependencies]]
   * @param runtimeDeps The already resolved runtime dependencies
   * @return The sublist of the runtimeDeps, which are extension runtime deps.
   */
  override def quarkusDeploymentDependencies(runtimeDeps: Seq[ApplicationModelWorker.Dependency])
      : Seq[ApplicationModelWorker.Dependency] = {
    runtimeDeps.filter(
      hasDeploymentDep
    )
  }

  /**
   * @param appModel The AppModel domain object
   * @param destination The directory to store the serialized application model file (*.dat)
   * @return The path of the file with the serialized Quarkus Application Model
   */
  override def quarkusGenerateApplicationModel(
      appModel: ApplicationModelWorker.AppModel,
      destination: os.Path
  ): os.Path = {
    val factory = BootstrapAppModelFactory.newInstance()
    factory.setProjectRoot(appModel.projectRoot.toNIO)

    def toResolvedDependencyBuilder(dep: ApplicationModelWorker.Dependency)
        : ResolvedDependencyBuilder = {
      val builder = ResolvedDependencyBuilder.newInstance()
        .setResolvedPath(dep.resolvedPath.toNIO)
        .setGroupId(dep.groupId)
        .setArtifactId(dep.artifactId)
        .setVersion(dep.version)

      if (appModel.appMode == Test) {
        builder.setDeploymentCp()
        builder.setRuntimeCp()
      }
      if (dep.isRuntime) {
        builder.setRuntimeCp()
      }

      if (dep.hasExtension) {
        builder.setFlags(DependencyFlags.RUNTIME_EXTENSION_ARTIFACT)
        builder.setDeploymentCp()
        if (dep.isTopLevelArtifact)
          builder.setFlags(DependencyFlags.TOP_LEVEL_RUNTIME_EXTENSION_ARTIFACT)
      }

      if (dep.isDeployment)
        builder.setDeploymentCp()

      builder
    }

    val dependencies = appModel.dependencies.map(toResolvedDependencyBuilder)

    val workspaceModuleBuilder = WorkspaceModule.builder()
      .setModuleDir(appModel.projectRoot.toNIO)
      .setModuleId(
        WorkspaceModuleId.of(appModel.groupId, appModel.artifactId, appModel.version)
      ).setBuildDir(appModel.buildDir.toNIO)
      .setBuildFile(appModel.buildFile.toNIO)

    appModel.moduleData.foreach(md =>
      workspaceModuleBuilder.addArtifactSources(
        artifactSources(md)
      )
    )

    val resolvedDependencyBuilder = ResolvedDependencyBuilder.newInstance().setWorkspaceModule(
      workspaceModuleBuilder
        .build()
    ).setResolvedPaths(
      PathList.of(
        appModel.moduleData.flatMap(md =>
          Seq(md.sources.destDir.toNIO, md.resources.destDir.toNIO)
        )*
      )
    )
      .setGroupId(appModel.groupId)
      .setArtifactId(appModel.artifactId)
      .setVersion(appModel.version)

    val platformImport = new PlatformImportsImpl()

    val boms: Seq[ArtifactCoords] = appModel.boms.map { bom =>
      val parts = bom.split(":") // todo make a bom model in the AppModel

      ArtifactCoords.pom(
        parts(0),
        parts(1),
        parts(2)
      )
    }

    boms.foreach(platformImport.getImportedPlatformBoms.add)

    platformImport.getPlatformReleaseInfo.add(
      PlatformReleaseInfo(
        "io.quarkus.platform",
        appModel.quarkusVersion,
        appModel.quarkusVersion,
        boms.toList.asJava
      )
    )

    platformImport.getPlatformProperties.put(
      "platform.quarkus.native.builder-image",
      appModel.nativeImage
    )

    val modelBuilder = new ApplicationModelBuilder()
      .setAppArtifact(resolvedDependencyBuilder)
      .setPlatformImports(platformImport)
      .addDependencies(dependencies.asJava)

    processQuarkusDependency(resolvedDependencyBuilder, modelBuilder)

    dependencies.foreach((resolvedDependencyBuilder: ResolvedDependencyBuilder) =>
      processQuarkusDependency(resolvedDependencyBuilder, modelBuilder)
    )

    val targetFile = appModel.appMode match {
      case AppMode.App => BootstrapUtils.resolveSerializedAppModelPath(destination.toNIO)
      case AppMode.Test => BootstrapUtils.getSerializedTestAppModelPath(destination.toNIO)
    }

    ApplicationModelSerializer.serialize(
      modelBuilder.build(),
      targetFile
    )

    os.Path(targetFile)

  }

  def artifactSources(moduleData: ModuleData): ArtifactSources = {
    val sources = SourceDir.of(moduleData.sources.dir.toNIO, moduleData.sources.destDir.toNIO)
    val resources = SourceDir.of(moduleData.resources.dir.toNIO, moduleData.resources.destDir.toNIO)
    moduleData.classifier match {
      case ModuleClassifier.Main =>
        ArtifactSources.main(sources, resources)
      case ModuleClassifier.Tests =>
        ArtifactSources.test(sources, resources)
    }
  }

  // utility function adapted from io.quarkus.gradle.tooling.GradleApplicationModelBuilder
  // for implementation details see [[https://github.com/quarkusio/quarkus/blob/main/devtools/gradle/gradle-model/src/main/java/io/quarkus/gradle/tooling/GradleApplicationModelBuilder.java]]
  private def processQuarkusDependency(
      artifactBuilder: ResolvedDependencyBuilder,
      modelBuilder: ApplicationModelBuilder
  ): Unit = {
    if (artifactBuilder.hasAllFlags(DependencyFlags.RUNTIME_CP)) {
      artifactBuilder.getResolvedPaths.asScala.filter(p =>
        Files.exists(p) && artifactBuilder.getType == ArtifactCoords.TYPE_JAR
      ).foreach {
        artifactPath =>
          if (Files.isDirectory(artifactPath)) {
            processQuarkusDir(
              artifactBuilder = artifactBuilder,
              quarkusDir = artifactPath.resolve(BootstrapConstants.META_INF),
              modelBuilder = modelBuilder
            )
          } else {
            Using.resource(ZipUtils.newFileSystem(artifactPath))(artifactFs =>
              processQuarkusDir(
                artifactBuilder = artifactBuilder,
                quarkusDir = artifactFs.getPath(BootstrapConstants.META_INF),
                modelBuilder = modelBuilder
              )
            )
          }
      }
    }

  }

  private def hasDeploymentDep(dep: ApplicationModelWorker.Dependency): Boolean = {
    val artifact = dep.resolvedPath
    def metaInfPathExists = Using.resource(ZipUtils.newFileSystem(artifact.toNIO)) { artifactFs =>
      val quarkusDir = artifactFs.getPath(BootstrapConstants.META_INF)
      val quarkusDescr = quarkusDir.resolve(BootstrapConstants.DESCRIPTOR_FILE_NAME)
      Files.exists(quarkusDescr)
    }
    dep.isRuntime && metaInfPathExists
  }

  private def processQuarkusDir(
      artifactBuilder: ResolvedDependencyBuilder,
      quarkusDir: java.nio.file.Path,
      modelBuilder: ApplicationModelBuilder
  ): Unit = {
    val quarkusDescr = quarkusDir.resolve(BootstrapConstants.DESCRIPTOR_FILE_NAME)

    if (Files.exists(quarkusDescr)) {
      val extProps = readDescriptior(quarkusDescr)

      artifactBuilder.setRuntimeExtensionArtifact()
      modelBuilder.handleExtensionProperties(extProps, artifactBuilder.getKey)

      val providesCapabilities =
        Option(extProps.getProperty(BootstrapConstants.PROP_PROVIDES_CAPABILITIES))

      providesCapabilities.foreach(cap =>
        modelBuilder
          .addExtensionCapabilities(CapabilityContract.of(artifactBuilder.toGACTVString, cap, null))
      )

    }
  }

  private def readDescriptior(path: java.nio.file.Path): Properties = {
    val rtProps = new Properties()

    Using.resource(Files.newBufferedReader(path))(br => rtProps.load(br))

    rtProps
  }

  override def close(): Unit = {
    // no op
  }
}
