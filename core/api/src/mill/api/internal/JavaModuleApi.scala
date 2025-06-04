package mill.api.internal

import mill.api.internal.bsp.BspJavaModuleApi
import mill.api.internal.idea.ResolvedModule
import mill.api.internal.{EvaluatorApi, ModuleApi, TaskApi, UnresolvedPathApi}

trait JavaModuleApi extends ModuleApi {

  def recursiveModuleDeps: Seq[JavaModuleApi]

  def compileModuleDepsChecked: Seq[JavaModuleApi]

  def transitiveModuleCompileModuleDeps: Seq[JavaModuleApi]

  def javacOptions: TaskApi[Seq[String]]
  def mandatoryJavacOptions: TaskApi[Seq[String]]

  // IDEA tasks

  def skipIdea: Boolean

  // FIXME: Move to internal trait like we do for BSP tasks
  private[mill] def genIdeaMetadata(
      ideaConfigVersion: Int,
      evaluator: EvaluatorApi,
      path: mill.api.Segments
  ): TaskApi[ResolvedModule]

  // FIXME: Move to internal trait like we do for BSP tasks
  private[mill] def intellijModulePathJava: java.nio.file.Path

  // BSP Tasks that sometimes need to be customized

  private[mill] def bspBuildTargetCompile(
      needsToMergeResourcesIntoCompileDest: Boolean
  ): TaskApi[java.nio.file.Path]

  private[mill] def bspCompileClassesPath(needsToMergeResourcesIntoCompileDest: Boolean)
      : TaskApi[UnresolvedPathApi[?]]

  private[mill] def bspCompileClasspath(
      needsToMergeResourcesIntoCompileDest: Boolean
  ): TaskApi[EvaluatorApi => Seq[String]]

  /**
   * Internal access to some BSP helper tasks
   */
  private[mill] def bspJavaModule: () => BspJavaModuleApi
}

object JavaModuleApi
