package mill.api.internal

/** Tracks the source location (file path and character index) of a parsed value */
case class Located[T](path: os.Path, index: Int, value: T)

/** Wraps a value with append semantics (whether to replace or append to existing value) */
case class Appendable[T](value: T, append: Boolean = false)

object Appendable {
  import upickle.core.BufferedValue

  /** Marker key indicating append mode in YAML `!append` tag wrapper objects */
  val AppendMarkerKey = "__mill_append__"

  /** Marker key containing the actual values in YAML `!append` tag wrapper objects */
  val ValuesMarkerKey = "__mill_values__"

  /**
   * Extract append flag and actual value from a BufferedValue that may contain
   * the marker object (produced by YAML `!append` tag parsing).
   * Returns (actualValue, appendFlag).
   */
  def unwrapAppendMarker(v: BufferedValue): (BufferedValue, Boolean) = {
    v match {
      case obj: BufferedValue.Obj =>
        val kvMap = obj.value0.collect { case (BufferedValue.Str(k, _), v) =>
          k.toString -> v
        }.toMap
        (kvMap.get(AppendMarkerKey), kvMap.get(ValuesMarkerKey)) match {
          case (Some(BufferedValue.True(_)), Some(values)) => (values, true)
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

object Located {

  /** Upickle reader for Located[T] that wraps values with their source location */
  class UpickleReader[T](path: os.Path)(implicit r: upickle.Reader[T])
      extends upickle.Reader[Located[T]] {

    private def wrap(index: Int, v: T): Located[T] = Located(path, index, v)

    def visitArray(length: Int, index: Int): upickle.core.ArrVisitor[Any, Located[T]] = {
      val delegate = r.visitArray(length, index)
      new upickle.core.ArrVisitor[Any, Located[T]] {
        def subVisitor = delegate.subVisitor
        def visitValue(v: Any, index: Int): Unit = delegate.visitValue(v, index)
        def visitEnd(idx: Int) = wrap(index, delegate.visitEnd(idx))
      }
    }

    def visitObject(
        length: Int,
        jsonableKeys: Boolean,
        index: Int
    ): upickle.core.ObjVisitor[Any, Located[T]] = {
      val delegate = r.visitObject(length, jsonableKeys, index)
      new upickle.core.ObjVisitor[Any, Located[T]] {
        def subVisitor = delegate.subVisitor
        def visitKey(index: Int) = delegate.visitKey(index)
        def visitKeyValue(s: Any): Unit = delegate.visitKeyValue(s)
        def visitValue(v: Any, index: Int): Unit = delegate.visitValue(v, index)
        def visitEnd(idx: Int) = wrap(index, delegate.visitEnd(idx))
      }
    }

    def visitNull(index: Int) = wrap(index, r.visitNull(index))
    def visitFalse(index: Int) = wrap(index, r.visitFalse(index))
    def visitTrue(index: Int) = wrap(index, r.visitTrue(index))
    def visitFloat64StringParts(s: CharSequence, decIndex: Int, expIndex: Int, index: Int) =
      wrap(index, r.visitFloat64StringParts(s, decIndex, expIndex, index))
    def visitFloat64(d: Double, index: Int) = wrap(index, r.visitFloat64(d, index))
    def visitFloat32(d: Float, index: Int) = wrap(index, r.visitFloat32(d, index))
    def visitInt32(i: Int, index: Int) = wrap(index, r.visitInt32(i, index))
    def visitInt64(i: Long, index: Int) = wrap(index, r.visitInt64(i, index))
    def visitUInt64(i: Long, index: Int) = wrap(index, r.visitUInt64(i, index))
    def visitFloat64String(s: String, index: Int) = wrap(index, r.visitFloat64String(s, index))
    def visitString(s: CharSequence, index: Int) = wrap(index, r.visitString(s, index))
    def visitChar(s: Char, index: Int) = wrap(index, r.visitChar(s, index))
    def visitBinary(bytes: Array[Byte], offset: Int, len: Int, index: Int) =
      wrap(index, r.visitBinary(bytes, offset, len, index))
    def visitExt(tag: Byte, bytes: Array[Byte], offset: Int, len: Int, index: Int) =
      wrap(index, r.visitExt(tag, bytes, offset, len, index))
  }
}
