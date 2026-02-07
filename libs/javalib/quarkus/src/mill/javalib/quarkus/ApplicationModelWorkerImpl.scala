package mill.javalib.quarkus

import io.quarkus.bootstrap.BootstrapAppModelFactory
import io.quarkus.bootstrap.app.ApplicationModelSerializer
import io.quarkus.bootstrap.model.{ApplicationModelBuilder, PlatformImportsImpl}
import io.quarkus.bootstrap.util.BootstrapUtils
import io.quarkus.bootstrap.workspace.{
  ArtifactSources,
  SourceDir,
  WorkspaceModule,
  WorkspaceModuleId
}
import io.quarkus.maven.dependency.{ArtifactCoords, ResolvedDependencyBuilder}
import io.quarkus.paths.PathList

import scala.jdk.CollectionConverters.*
import scala.jdk.CollectionConverters

class ApplicationModelWorkerImpl extends ApplicationModelWorker {

  override def bootstrapQuarkus(
      appModel: ApplicationModelWorker.AppModel,
      destination: os.Path
  ): Unit = {
    val factory = BootstrapAppModelFactory.newInstance()
    factory.setProjectRoot(appModel.projectRoot.toNIO)

    def toResolvedDependencyBuilder(dep: ApplicationModelWorker.Dependency)
        : ResolvedDependencyBuilder = {
      ResolvedDependencyBuilder.newInstance()
        .setResolvedPath(dep.resolvedPath.toNIO)
        .setGroupId(dep.groupId)
        .setArtifactId(dep.artifactId)
        .setVersion(dep.version)
    }

    val resolvedDependencyBuilder = ResolvedDependencyBuilder.newInstance().setWorkspaceModule(
      WorkspaceModule.builder()
        .setModuleDir(appModel.projectRoot.toNIO)
        .setModuleId(
          WorkspaceModuleId.of(appModel.groupId, appModel.artifactId, appModel.version)
        ).addArtifactSources(
          ArtifactSources.main(
            // TODO generated sources?
            SourceDir.of(appModel.sourcesDir.toNIO, appModel.compiledPath.toNIO),
            SourceDir.of(appModel.resourcesDir.toNIO, appModel.compiledResources.toNIO)
          )
        ).setDependencies(appModel.dependencies.map(toResolvedDependencyBuilder).asJava)
        .build()
    ).setResolvedPaths(PathList.of(appModel.compiledPath.toNIO, appModel.compiledResources.toNIO))
      .setGroupId(appModel.groupId)
      .setArtifactId(appModel.artifactId)
      .setVersion(appModel.version)

    val platformImport = new PlatformImportsImpl()

    appModel.boms.foreach(bom =>
      val parts = bom.split(":") // todo make a bom model in the AppModel
      platformImport.getImportedPlatformBoms.add(
        ArtifactCoords.pom(
          parts(0),
          parts(1),
          parts(2)
        )
      )
    )

    val modelBuilder = new ApplicationModelBuilder()
      .setAppArtifact(resolvedDependencyBuilder)
      .setPlatformImports(platformImport)
      .addDependencies(appModel.dependencies.map(toResolvedDependencyBuilder).asJava)

    ApplicationModelSerializer.serialize(
      modelBuilder.build(),
      BootstrapUtils.resolveSerializedAppModelPath(destination.toNIO)
    )

  }

  override def close(): Unit = {
    // no op
  }
}
