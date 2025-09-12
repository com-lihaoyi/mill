package mill.api.daemon.internal

import mill.api.daemon.BuildInfo

trait SemanticDbJavaModuleApi {
  @deprecated("Move to BSP context")
  private[mill] def bspBuildTargetCompileSemanticDb: TaskApi[java.nio.file.Path]
  @deprecated("Move to BSP context")
  private[mill] def bspCompiledClassesAndSemanticDbFiles: TaskApi[UnresolvedPathApi[?]]
}
object SemanticDbJavaModuleApi {
  val buildTimeJavaSemanticDbVersion: String = BuildInfo.semanticDbJavaVersion
  val buildTimeSemanticDbVersion: String = BuildInfo.semanticDBVersion

  private[mill] val contextSemanticDbVersion: InheritableThreadLocal[Option[String]] =
    new InheritableThreadLocal[Option[String]] {
      protected override def initialValue(): Option[String] = None
    }

  private[mill] val contextJavaSemanticDbVersion: InheritableThreadLocal[Option[String]] =
    new InheritableThreadLocal[Option[String]] {
      protected override def initialValue(): Option[String] = None
    }

  private[mill] def resetContext(): Unit = {
    contextJavaSemanticDbVersion.set(None)
    contextSemanticDbVersion.set(None)
  }

}
