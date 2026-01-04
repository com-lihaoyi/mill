package mill.api.daemon.internal.bsp

import mill.api.daemon.internal.{EvaluatorApi, ModuleApi, OptApi, OptsApi, TaskApi}

import java.nio.file.Path

trait BspJavaModuleApi extends ModuleApi {

  private[mill] def bspBuildTargetInverseSources[T](id: T, uri: String): TaskApi[Seq[T]]

  private[mill] def bspBuildTargetDependencySources
      : TaskApi[(
          resolvedDepsSources: Seq[java.nio.file.Path],
          unmanagedClasspath: Seq[java.nio.file.Path]
      )]

  private[mill] def bspBuildTargetDependencyModules
      : TaskApi[(
          mvnDeps: Seq[(String, String, String)],
          unmanagedClasspath: Seq[java.nio.file.Path]
      )]

  private[mill] def bspBuildTargetSources
      : TaskApi[(
          sources: Seq[java.nio.file.Path],
          generatedSources: Seq[java.nio.file.Path]
      )]

  private[mill] def bspBuildTargetResources: TaskApi[Seq[java.nio.file.Path]]

  private[mill] def bspBuildTargetJavacOptions(
      needsToMergeResourcesIntoCompileDest: Boolean,
      clientWantsSemanticDb: Boolean
  )
      : TaskApi[EvaluatorApi => (
          classesPath: Path,
          javacOptions: OptsApi,
          classpath: Seq[String]
      )]

  private[mill] def bspBuildTargetScalacOptions(
      needsToMergeResourcesIntoCompileDest: Boolean,
      enableJvmCompileClasspathProvider: Boolean,
      clientWantsSemanticDb: Boolean
  ): TaskApi[(
      scalacOptionsTask: OptsApi,
      compileClasspathTask: EvaluatorApi => Seq[String],
      classPathTask: EvaluatorApi => java.nio.file.Path
  )]

  private[mill] def bspBuildTargetScalaMainClasses
      : TaskApi[(
          classes: Seq[String],
          forkArgs: OptsApi,
          forkEnv: Map[String, OptApi]
      )]

  private[mill] def bspLoggingTest: TaskApi[Unit]

}
