package mill.define

case class OverrideMapping(value: Map[(Class[_], String), Segments])
object OverrideMapping {
  trait Wrapper {
    implicit def overrideMapping: OverrideMapping
  }
}
