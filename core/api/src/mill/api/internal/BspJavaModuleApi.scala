package mill.api.internal

import java.nio.file.Path

trait BspJavaModuleApi extends ModuleApi {

  private[mill] def bspRun(args: Seq[String]): TaskApi[Unit]

  private[mill] def bspBuildTargetJavacOptions(
      needsToMergeResourcesIntoCompileDest: Boolean,
      clientWantsSemanticDb: Boolean
  )
      : TaskApi[EvaluatorApi => (
          classesPath: Path,
          javacOptions: Seq[String],
          classpath: Seq[String]
      )]

  private[mill] def bspBuildTargetScalacOptions(
      needsToMergeResourcesIntoCompileDest: Boolean,
      enableJvmCompileClasspathProvider: Boolean,
      clientWantsSemanticDb: Boolean
  ): TaskApi[(Seq[String], EvaluatorApi => Seq[String], EvaluatorApi => java.nio.file.Path)]

  private[mill] def bspLoggingTest: TaskApi[Unit]

}
