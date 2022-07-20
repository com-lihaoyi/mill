package mill.define

import scala.reflect.macros.blackbox.Context

object Compat {
  def copyAnnotatedType(c: Context)(
      tpe: c.universe.AnnotatedType,
      newAnnots: List[c.universe.Annotation]
  ): c.universe.AnnotatedType = {
    c.universe.internal.annotatedType(newAnnots, tpe.underlying)
  }
}
