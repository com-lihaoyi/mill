package mill.api.internal

import mill.api.internal.bsp.BspJavaModuleApi
import mill.api.internal.idea.ResolvedModule
import mill.api.internal.{EvaluatorApi, ModuleApi, TaskApi, UnresolvedPathApi}

trait JavaModuleApi extends ModuleApi {

  @deprecated("Move to BSP context")
  private[mill] def bspBuildTargetScalaMainClasses
      : TaskApi[(Seq[String], Seq[String], Map[String, String])]

  def recursiveModuleDeps: Seq[JavaModuleApi]

  def compileModuleDepsChecked: Seq[JavaModuleApi]

  @deprecated("Move to BSP context")
  private[mill] def bspBuildTargetCompile(
      needsToMergeResourcesIntoCompileDest: Boolean
  ): TaskApi[java.nio.file.Path]

  private[mill] def bspCompileClasspath(
      needsToMergeResourcesIntoCompileDest: Boolean
  )
      : TaskApi[EvaluatorApi => Seq[String]]

  private[mill] def genIdeaMetadata(
      ideaConfigVersion: Int,
      evaluator: EvaluatorApi,
      path: mill.api.Segments
  ): TaskApi[ResolvedModule]

  def transitiveModuleCompileModuleDeps: Seq[JavaModuleApi]
  def skipIdea: Boolean
  private[mill] def intellijModulePathJava: java.nio.file.Path

  def javacOptions: TaskApi[Seq[String]]
  def mandatoryJavacOptions: TaskApi[Seq[String]]

  @deprecated("Move to BSP context")
  private[mill] def bspCompileClassesPath(needsToMergeResourcesIntoCompileDest: Boolean)
      : TaskApi[UnresolvedPathApi[?]]

  private[mill] def bspJavaModule: () => BspJavaModuleApi
}

object JavaModuleApi
