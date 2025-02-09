package mill.define

case class Caller[+T](value: T)
object Caller {
  def apply[T]()(implicit c: Caller[T]) = c.value

  /* basically a poison-pill to check that the Module defined version is enough */
  inline given generate: Caller[Nothing] = defaultCaller

  @annotation.compileTimeOnly("Modules and Tasks can only be defined within a mill Module")
  def defaultCaller: Caller[Nothing] = Caller(null.asInstanceOf[Nothing])
}
