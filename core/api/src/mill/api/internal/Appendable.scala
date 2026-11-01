package mill.api.internal

/** Wraps a value with append semantics (whether to replace or append to existing value) */
case class Appendable[T](value: T, append: Boolean = false)

object Appendable {
  import upickle.core.BufferedValue

  /** Marker key for append mode: `{$millAppend: <actual-value>}` */
  val AppendMarkerKey = "$millAppend"

  /**
   * YAML tag to denote append-semantics for seq, map and custom types.
   * This roughly means including the super-task result when overriding tasks in YAML.
   */
  val AppendYamlTag: "!append" = "!append"

  /**
   * Extract append flag and actual value from a BufferedValue that may contain
   * the marker object `{$millAppend: <value>}` (produced by YAML `!append` tag parsing).
   * Returns (actualValue, appendFlag).
   */
  def unwrapAppendMarker(v: BufferedValue): (BufferedValue, Boolean) = {
    v match {
      case obj: BufferedValue.Obj if obj.value0.size == 1 =>
        obj.value0.head match {
          case (BufferedValue.Str(k, _), value) if k.toString == AppendMarkerKey =>
            (value, true)
          case _ => (v, false)
        }
      case _ => (v, false)
    }
  }

  /**
   * Upickle reader for Appendable[T] that buffers the value, checks for the
   * `!append` marker object, and deserializes accordingly.
   */
  class UpickleReader[T](implicit r: upickle.Reader[T])
      extends upickle.Reader.MapReader[BufferedValue, BufferedValue, Appendable[T]](
        BufferedValue.Builder
      ) {
    def mapNonNullsFunction(buffered: BufferedValue): Appendable[T] = {
      val (actualValue, append) = unwrapAppendMarker(buffered)
      val deserialized = BufferedValue.transform(actualValue, r)
      Appendable(deserialized, append)
    }
  }
}
