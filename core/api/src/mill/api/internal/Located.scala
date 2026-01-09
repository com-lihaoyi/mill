package mill.api.internal

case class Located[T](path: os.Path, index: Int, value: T, append: Boolean = false)

object Located {
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

  class UpickleReader[T](path: os.Path)(implicit r: upickle.Reader[T])
      extends upickle.Reader[Located[T]] {
    private def wrap(index: Int, v: T, append: Boolean = false): Located[T] =
      Located(path, index, v, append)

    def visitArray(length: Int, index: Int): upickle.core.ArrVisitor[Any, Located[T]] = {
      val delegate = r.visitArray(length, index)
      new upickle.core.ArrVisitor[Any, Located[T]] {
        def subVisitor = delegate.subVisitor
        def visitValue(v: Any, index: Int): Unit = delegate.visitValue(v, index)
        def visitEnd(idx: Int) = wrap(index, delegate.visitEnd(idx))
      }
    }

    def visitObject(length: Int, jsonableKeys: Boolean, index: Int)
        : upickle.core.ObjVisitor[Any, Located[T]] = {
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

  /**
   * Upickle reader that detects the `!append` marker object
   * and extracts the append flag along with the actual value.
   */
  class AppendUpickleReader[T](path: os.Path)(implicit r: upickle.Reader[T])
      extends upickle.Reader[Located[T]] {
    private def wrap(index: Int, v: T, append: Boolean): Located[T] =
      Located(path, index, v, append)

    // For arrays without !append marker - just wrap with append=false
    def visitArray(length: Int, index: Int): upickle.core.ArrVisitor[Any, Located[T]] = {
      val delegate = r.visitArray(length, index)
      new upickle.core.ArrVisitor[Any, Located[T]] {
        def subVisitor = delegate.subVisitor
        def visitValue(v: Any, index: Int): Unit = delegate.visitValue(v, index)
        def visitEnd(idx: Int) = wrap(index, delegate.visitEnd(idx), append = false)
      }
    }

    // For objects - check if it's an !append marker object
    def visitObject(length: Int, jsonableKeys: Boolean, index: Int)
        : upickle.core.ObjVisitor[Any, Located[T]] = {
      new upickle.core.ObjVisitor[Any, Located[T]] {
        private var currentKey: String = ""
        private var hasAppendMarker = false
        private var valuesResult: Option[T] = None
        private val startIndex = index

        // Delegate for parsing the inner values array
        private val valuesReader = r

        def subVisitor: upickle.core.Visitor[?, ?] = currentKey match {
          case AppendMarkerKey => upickle.core.NoOpVisitor
          case ValuesMarkerKey => valuesReader
          case _ => upickle.core.NoOpVisitor
        }

        def visitKey(index: Int): upickle.core.Visitor[?, ?] = upickle.core.StringVisitor

        def visitKeyValue(s: Any): Unit = {
          currentKey = s.toString
        }

        def visitValue(v: Any, index: Int): Unit = {
          currentKey match {
            case AppendMarkerKey => hasAppendMarker = true
            case ValuesMarkerKey => valuesResult = Some(v.asInstanceOf[T])
            case _ => ()
          }
        }

        def visitEnd(idx: Int): Located[T] = {
          if (hasAppendMarker && valuesResult.isDefined) {
            wrap(startIndex, valuesResult.get, append = true)
          } else {
            // Not an append marker object - this shouldn't happen for moduleDeps
            // but fall back to empty value
            wrap(startIndex, r.visitArray(0, startIndex).visitEnd(startIndex), append = false)
          }
        }
      }
    }

    def visitNull(index: Int) = wrap(index, r.visitNull(index), append = false)
    def visitFalse(index: Int) = wrap(index, r.visitFalse(index), append = false)
    def visitTrue(index: Int) = wrap(index, r.visitTrue(index), append = false)
    def visitFloat64StringParts(s: CharSequence, decIndex: Int, expIndex: Int, index: Int) =
      wrap(index, r.visitFloat64StringParts(s, decIndex, expIndex, index), append = false)
    def visitFloat64(d: Double, index: Int) = wrap(index, r.visitFloat64(d, index), append = false)
    def visitFloat32(d: Float, index: Int) = wrap(index, r.visitFloat32(d, index), append = false)
    def visitInt32(i: Int, index: Int) = wrap(index, r.visitInt32(i, index), append = false)
    def visitInt64(i: Long, index: Int) = wrap(index, r.visitInt64(i, index), append = false)
    def visitUInt64(i: Long, index: Int) = wrap(index, r.visitUInt64(i, index), append = false)
    def visitFloat64String(s: String, index: Int) =
      wrap(index, r.visitFloat64String(s, index), append = false)
    def visitString(s: CharSequence, index: Int) =
      wrap(index, r.visitString(s, index), append = false)
    def visitChar(s: Char, index: Int) = wrap(index, r.visitChar(s, index), append = false)
    def visitBinary(bytes: Array[Byte], offset: Int, len: Int, index: Int) =
      wrap(index, r.visitBinary(bytes, offset, len, index), append = false)
    def visitExt(tag: Byte, bytes: Array[Byte], offset: Int, len: Int, index: Int) =
      wrap(index, r.visitExt(tag, bytes, offset, len, index), append = false)
  }
}
