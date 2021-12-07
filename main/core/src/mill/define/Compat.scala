package mill.define

import scala.reflect.macros.blackbox.Context

object Compat {
  def copyAnnotatedType(c: Context)(
      tpe: c.universe.AnnotatedType,
      newAnnots: List[c.universe.Annotation]
  ) = {
    import c.universe.compat._

    c.universe.AnnotatedType(newAnnots, tpe.underlying)
  }
}
