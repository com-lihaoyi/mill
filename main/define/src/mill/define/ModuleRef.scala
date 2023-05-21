package mill.define

/**
 * Used to refer to a module from another module without including the target
 * module as a child-module of the first.
 */
case class ModuleRef[+T <: mill.define.Module](t: T) {
  def apply(): T = t
}
