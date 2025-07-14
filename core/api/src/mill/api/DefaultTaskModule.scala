package mill.api

/**
 * A [[Module]] that has a [[defaultTask]] that will be automatically
 * executed if the module name is provide at the Mill command line
 */
trait DefaultTaskModule extends Module {

  /**
   * The name of the default command, which will be automatically executed if
   * the module name is provided at the Mill command line.
   */
  def defaultTask(): String
}
