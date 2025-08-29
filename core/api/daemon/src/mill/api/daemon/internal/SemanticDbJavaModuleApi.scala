package mill.api.daemon.internal

import mill.api.daemon.BuildInfo

trait SemanticDbJavaModuleApi {
  @deprecated("Move to BSP context")
  private[mill] def bspBuildTargetCompileSemanticDb: TaskApi[java.nio.file.Path]
  @deprecated("Move to BSP context")
  private[mill] def bspCompiledClassesAndSemanticDbFiles: TaskApi[UnresolvedPathApi[?]]
}
object SemanticDbJavaModuleApi {
  val buildTimeJavaSemanticDbVersion = BuildInfo.semanticDbJavaVersion
  val buildTimeSemanticDbVersion = BuildInfo.semanticDBVersion

  private[mill] val contextSemanticDbVersion: InheritableThreadLocal[Option[String]] =
    new InheritableThreadLocal[Option[String]] {
      protected override def initialValue(): Option[String] = None.asInstanceOf[Option[String]]
    }

  private[mill] def clientNeedsSemanticDb(): Boolean = contextSemanticDbVersion.get().isDefined

  private[mill] val contextJavaSemanticDbVersion: InheritableThreadLocal[Option[String]] =
    new InheritableThreadLocal[Option[String]] {
      protected override def initialValue(): Option[String] = None.asInstanceOf[Option[String]]
    }

  private[mill] def resetContext(): Unit = {
    contextJavaSemanticDbVersion.set(None)
    contextSemanticDbVersion.set(None)
  }

}
