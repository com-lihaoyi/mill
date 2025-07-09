package mill.api.daemon.internal.idea

trait GenIdeaModuleApi {

  def skipIdea: Boolean

  /**
   * The path denoting the module directory in generated IntelliJ projects. Defaults to [[moduleDir]].
   */
  private[mill] def intellijModulePathJava: java.nio.file.Path

}
