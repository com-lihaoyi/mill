package mill.define

import language.experimental.macros

case class Discover[T](value: Map[Class[_], Seq[(Int, mainargs.MainData[_, _])]])
object Discover {
  def apply[T]: Discover[T] = macro mill.util.Router.applyImpl[T]
}
