package mill.api.internal

case class Located[T](path: os.Path, index: Int, value: T)

object Located {
   implicit def locatedReader[T](implicit r: upickle.Reader[T]): upickle.Reader[Located[T]] =
    new upickle.Reader[Located[T]] {
      private def wrap(index: Int, v: T): Located[T] = Located(null, index, v)

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
  type LocatedValue = Located[upickle.core.BufferedValue]
}

// Backward compatibility alias
type LocatedValue = Located[upickle.core.BufferedValue]
