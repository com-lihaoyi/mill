package mill.api.internal

trait RunModuleApi extends ModuleApi {
  @deprecated("Move to BSP context")
  private[mill] def bspJvmRunTestEnvironment: TaskApi[(
      Seq[java.nio.file.Path],
      Seq[String],
      java.nio.file.Path,
      Map[String, String],
      Option[String],
      Any
  )]
}
