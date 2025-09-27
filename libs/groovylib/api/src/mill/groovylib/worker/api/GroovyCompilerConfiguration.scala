package mill.groovylib.worker.api

@mill.api.experimental
case class GroovyCompilerConfiguration(
    enablePreview: Boolean = false,
    disabledGlobalAstTransformations: Set[String] = Set.empty,
    targetBytecode: Option[String] = None
)
