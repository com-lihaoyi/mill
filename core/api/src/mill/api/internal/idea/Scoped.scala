package mill.api.internal.idea

final case class Scoped[T](value: T, scope: Option[String])
