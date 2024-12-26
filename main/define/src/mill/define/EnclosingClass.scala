package mill.define

case class EnclosingClass(value: Class[_])
object EnclosingClass {
  def apply()(implicit c: EnclosingClass) = c.value
  implicit def generate: EnclosingClass = EnclosingClass{
    StackWalker.getInstance(java.util.Set.of(StackWalker.Option.RETAIN_CLASS_REFERENCE))
      .walk(_.skip(1).findFirst())
      .get()
      .getDeclaringClass()
  }
}

