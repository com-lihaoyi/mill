package mill.define

/**
 * A [[Module]] that has a [[defaultCommandName]] that will be automatically
 * executed if the module name is provide at the Mill command line
 */
trait TaskModule extends Module {

  /**
   * The name of the default command, which will be automatically excecuted if
   * the module name is provided at the Mill command line
   */
  def defaultCommandName(): String
}
