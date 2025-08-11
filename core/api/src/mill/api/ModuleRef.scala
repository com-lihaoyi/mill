package mill.api

/**
 * Used to refer to a module from another module without including the target
 * module as a child-module of the first.
 */
final case class ModuleRef[+T <: mill.api.Module](t: T) {
  def apply(): T = t
}
