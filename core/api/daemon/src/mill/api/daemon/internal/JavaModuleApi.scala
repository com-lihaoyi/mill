package mill.api.daemon.internal

import mill.api.daemon.internal.bsp.BspJavaModuleApi
import mill.api.daemon.internal.idea.{GenIdeaInternalApi, GenIdeaModuleApi}
import mill.api.daemon.internal.{EvaluatorApi, ModuleApi, TaskApi, UnresolvedPathApi}

trait JavaModuleApi extends ModuleApi with GenIdeaModuleApi {

  def recursiveModuleDeps: Seq[JavaModuleApi]

  def compileModuleDepsChecked: Seq[JavaModuleApi]

  def transitiveModuleCompileModuleDeps: Seq[JavaModuleApi]

  def javacOptions: TaskApi[Seq[String]]
  def mandatoryJavacOptions: TaskApi[Seq[String]]

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

  /**
   * Internal access to some GenIdea helper tasks
   */
  private[mill] def genIdeaInternal: () => GenIdeaInternalApi

}

object JavaModuleApi
