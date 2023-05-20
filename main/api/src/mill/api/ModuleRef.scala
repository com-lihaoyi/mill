package mill.api

/**
 * Used to refer to a module from another module without including the target
 * module as a child-module of the first.
 */
case class ModuleRef[+T](t: T) {
  def apply() = t
}
