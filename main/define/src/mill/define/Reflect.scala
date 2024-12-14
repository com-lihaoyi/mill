package mill.define

import scala.reflect.ClassTag

private[mill] object Reflect {
  import java.lang.reflect.Modifier

  def isLegalIdentifier(identifier: String): Boolean = {
    var i = 0
    val len = identifier.length
    while(i < len){
      val c = identifier.charAt(i)
      if ('a' <= c && c <= 'z' || 'A' <= c && c <= 'Z' || '0' <= c && c <= '9' || c == '_' || c == '-'){
        i += 1
      } else{
        return false
      }
    }
    true
  }

  def getMethods(cls: Class[_], decode: String => String) =
    for {
      m <- cls.getMethods
      n = decode(m.getName)
      if isLegalIdentifier(n) && (m.getModifiers & Modifier.STATIC) == 0
    } yield (m, n)

  def reflect(
      outer: Class[_],
      inner: Class[_],
      filter: String => Boolean,
      noParams: Boolean,
      getMethods: Class[_] => Seq[(java.lang.reflect.Method, String)]
  ): Seq[java.lang.reflect.Method] = {
    val res = for {
      (m, n) <- getMethods(outer)
      if filter(n) &&
        (!noParams || m.getParameterCount == 0) &&
        inner.isAssignableFrom(m.getReturnType)
    } yield m

    // There can be multiple methods of the same name on a class if a sub-class
    // overrides a super-class method and narrows the return type.
    //
    // 1. Make sure we sort the methods by their declaring class from lowest to
    //    highest in the type hierarchy, and use `distinctBy` to only keep
    //    the lowest version, before we finally sort them by name
    //
    // 2. Sometimes traits also generate synthetic forwarders for overrides,
    //    which messes up the comparison since all forwarders will have the
    //    same `getDeclaringClass`. To handle these scenarios, also sort by
    //    return type, so we can identify the most specific override
    res
      .sortWith((m1, m2) =>
        if (m1.getDeclaringClass.equals(m2.getDeclaringClass)) false
        else m1.getDeclaringClass.isAssignableFrom(m2.getDeclaringClass)
      )
      .sortWith((m1, m2) =>
        m1.getReturnType.isAssignableFrom(m2.getReturnType)
      )
      .reverse
      .distinctBy(_.getName)
      .sortBy(_.getName)
      .toIndexedSeq
  }

  // For some reason, this fails to pick up concrete `object`s nested directly within
  // another top-level concrete `object`. This is fine for now, since Mill's Ammonite
  // script/REPL runner always wraps user code in a wrapper object/trait
  def reflectNestedObjects0[T: ClassTag](
      outerCls: Class[_],
      filter: String => Boolean = Function.const(true),
      getMethods: Class[_] => Seq[(java.lang.reflect.Method, String)]

                                        ): Seq[(String, java.lang.reflect.Member)] = {

    val first = reflect(
      outerCls,
      implicitly[ClassTag[T]].runtimeClass,
      filter,
      noParams = true,
      getMethods,

    )
      .map(m => (m.getName, m))

    val second =
      outerCls
        .getClasses
        .filter(implicitly[ClassTag[T]].runtimeClass.isAssignableFrom(_))
        .flatMap { c =>
          c.getName.stripPrefix(outerCls.getName) match {
            case s"$name$$" if filter(name) =>
              c.getFields.find(_.getName == "MODULE$").map(name -> _)
            case _ => None
          }

        }
        .distinct

    first ++ second
  }

  def reflectNestedObjects02[T: ClassTag](
      outerCls: Class[_],
      filter: String => Boolean = Function.const(true),
      getMethods: Class[_] => Seq[(java.lang.reflect.Method, String)]

                                         ): Seq[(String, Class[_], Any => T)] = {
    reflectNestedObjects0[T](outerCls, filter, getMethods).map {
      case (name, m: java.lang.reflect.Method) =>
        (name, m.getReturnType, (outer: Any) => m.invoke(outer).asInstanceOf[T])
      case (name, m: java.lang.reflect.Field) =>
        (name, m.getType, (outer: Any) => m.get(outer).asInstanceOf[T])
    }
  }

}
