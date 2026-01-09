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
   * Upickle reader for Appendable[T] that detects the `!append` marker object
   * and extracts the append flag along with the actual value.
   */
  class UpickleReader[T](implicit r: upickle.Reader[T])
      extends DelegatingReader[T, Appendable[T]](r) {

    protected def wrap(index: Int, v: T): Appendable[T] = Appendable(v)

    // Override object handling to detect !append marker
    override def visitObject(length: Int, jsonableKeys: Boolean, index: Int)
        : upickle.core.ObjVisitor[Any, Appendable[T]] = {
      new upickle.core.ObjVisitor[Any, Appendable[T]] {
        private var currentKey: String = ""
        private var hasAppendMarker = false
        private var valuesResult: Option[T] = None
        private val startIndex = index

        private lazy val delegate = r.visitObject(length, jsonableKeys, index)
        private val seenKeys = scala.collection.mutable.Set[String]()

        def subVisitor: upickle.core.Visitor[?, ?] = currentKey match {
          case AppendMarkerKey => upickle.core.NoOpVisitor
          case ValuesMarkerKey => r
          case _ => delegate.subVisitor
        }

        def visitKey(index: Int): upickle.core.Visitor[?, ?] = upickle.core.StringVisitor

        def visitKeyValue(s: Any): Unit = {
          currentKey = s.toString
          seenKeys += currentKey
          if (currentKey != AppendMarkerKey && currentKey != ValuesMarkerKey) {
            delegate.visitKeyValue(s)
          }
        }

        def visitValue(v: Any, index: Int): Unit = {
          currentKey match {
            case AppendMarkerKey => hasAppendMarker = true
            case ValuesMarkerKey => valuesResult = Some(v.asInstanceOf[T])
            case _ => delegate.visitValue(v, index)
          }
        }

        def visitEnd(idx: Int): Appendable[T] = {
          if (hasAppendMarker && valuesResult.isDefined) {
            Appendable(valuesResult.get, append = true)
          } else if (seenKeys == Set(AppendMarkerKey, ValuesMarkerKey)) {
            Appendable(r.visitArray(0, startIndex).visitEnd(startIndex), append = false)
          } else {
            Appendable(delegate.visitEnd(idx))
          }
        }
      }
    }
  }
}

object Located {

  /** Upickle reader for Located[T] that wraps values with their source location */
  class UpickleReader[T](path: os.Path)(implicit r: upickle.Reader[T])
      extends DelegatingReader[T, Located[T]](r) {

    protected def wrap(index: Int, v: T): Located[T] = Located(path, index, v)
  }
}

/**
 * Base class for upickle readers that delegate to an inner reader and wrap results.
 * Subclasses only need to implement `wrap` to transform inner values to outer values.
 */
abstract class DelegatingReader[T, O](r: upickle.Reader[T]) extends upickle.Reader[O] {

  /** Wrap an inner value with its parse index into the outer type */
  protected def wrap(index: Int, v: T): O

  def visitArray(length: Int, index: Int): upickle.core.ArrVisitor[Any, O] = {
    val delegate = r.visitArray(length, index)
    new upickle.core.ArrVisitor[Any, O] {
      def subVisitor = delegate.subVisitor
      def visitValue(v: Any, index: Int): Unit = delegate.visitValue(v, index)
      def visitEnd(idx: Int) = wrap(index, delegate.visitEnd(idx))
    }
  }

  def visitObject(
      length: Int,
      jsonableKeys: Boolean,
      index: Int
  ): upickle.core.ObjVisitor[Any, O] = {
    val delegate = r.visitObject(length, jsonableKeys, index)
    new upickle.core.ObjVisitor[Any, O] {
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
