package mill.api.internal.idea

trait GenIdeaModuleApi {

  def skipIdea: Boolean

  private[mill] def intellijModulePathJava: java.nio.file.Path

}


