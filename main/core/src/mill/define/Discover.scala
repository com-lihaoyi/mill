package mill.define

import language.experimental.macros

case class Discover[T] private (value: Map[Class[_], Seq[(Int, mainargs.MainData[_, _])]]) {
  private[mill] def copy(value: Map[Class[_], Seq[(Int, mainargs.MainData[_, _])]] = value)
      : Discover[T] =
    new Discover[T](value)
}
object Discover {
  def apply[T](value: Map[Class[_], Seq[(Int, mainargs.MainData[_, _])]]): Discover[T] =
    new Discover[T](value)
  def apply[T]: Discover[T] = macro mill.define.Router.applyImpl[T]
  private def unapply[T](discover: Discover[T])
      : Option[Map[Class[_], Seq[(Int, mainargs.MainData[_, _])]]] = Some(discover.value)
}
