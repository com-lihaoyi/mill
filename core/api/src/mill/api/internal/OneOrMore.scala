package mill.api.internal

import upickle.core.{BufferedValue, Visitor}

private[mill] case class OneOrMore[T](value: Seq[T])

private[mill] object OneOrMore {
  private val bufferedValueReader: upickle.Reader[BufferedValue] =
    new upickle.Reader.Delegate(BufferedValue.Builder)

  implicit def reader[T](using tr: upickle.Reader[T]): upickle.Reader[OneOrMore[T]] =
    bufferedValueReader.map { bv =>
      bv match {
        case arr: BufferedValue.Arr =>
          OneOrMore(arr.value.map(v => BufferedValue.transform(v, tr)).toSeq)
        case v =>
          OneOrMore(Seq(BufferedValue.transform(v, tr)))
      }
    }

  implicit def writer[T](using tw: upickle.Writer[T]): upickle.Writer[OneOrMore[T]] =
    new upickle.Writer[OneOrMore[T]] {
      override def write0[V](out: Visitor[?, V], v: OneOrMore[T]): V = {
        val arrVisitor = out.visitArray(v.value.length, -1).narrow
        v.value.foreach { elem =>
          arrVisitor.visitValue(tw.write(arrVisitor.subVisitor, elem), -1)
        }
        arrVisitor.visitEnd(-1)
      }
    }

  implicit def rw[T](using trw: upickle.ReadWriter[T]): upickle.ReadWriter[OneOrMore[T]] =
    upickle.ReadWriter.join(using reader[T], writer[T])
}
