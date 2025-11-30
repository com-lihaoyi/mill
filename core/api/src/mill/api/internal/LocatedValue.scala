package mill.api.internal

case class Located[T](path: os.Path, index: Int, value: T)

object Located {
  type LocatedValue = Located[upickle.core.BufferedValue]
}

// Backward compatibility alias
type LocatedValue = Located[upickle.core.BufferedValue]
