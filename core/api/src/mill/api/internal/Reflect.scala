package mill.api.internal

import java.lang.reflect.Method
import scala.reflect.ClassTag

private[mill] object Reflect {
  import java.lang.reflect.Modifier

  def isLegalIdentifier(identifier: String): Boolean = {
    var i = 0
    val len = identifier.length
    while (i < len) {
      val c = identifier.charAt(i)
      if (
        'a' <= c && c <= 'z' || 'A' <= c && c <= 'Z' || '0' <= c && c <= '9' || c == '_' || c == '-'
      ) {
        i += 1
      } else {
        return false
      }
    }
    true
  }

  def getMethods(
      cls: Class[?],
      noParams: Boolean,
      inner: Class[?],
      decode: String => String
  ): Array[(Method, String)] =
    for {
      m <- cls.getMethods
      n = decode(m.getName)
      if isLegalIdentifier(n) && (m.getModifiers & Modifier.STATIC) == 0
        && (!noParams || m.getParameterCount == 0) &&
        inner.isAssignableFrom(m.getReturnType)
    } yield (m, n)

  private val classSeqOrdering =
    Ordering.Implicits.seqOrdering[Seq, Class[?]](using
      (c1, c2) =>
        if (c1 == c2) 0 else if (c1.isAssignableFrom(c2)) 1 else -1
    )

  def reflect(
      outer: Class[?],
      inner: Class[?],
      filter: String => Boolean,
      noParams: Boolean,
      getMethods: (Class[?], Boolean, Class[?]) => Array[(java.lang.reflect.Method, String)]
  ): Array[java.lang.reflect.Method] = {
    val arr: Array[java.lang.reflect.Method] = getMethods(outer, noParams, inner)
      .collect { case (m, n) if filter(n) => m }

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
    //
    // 3. A class can have multiple methods with same name/return-type if they
    //    differ in parameter types, so sort by those as well
    arr.sortInPlaceWith((m1, m2) =>
      if (m1.getName != m2.getName) m1.getName < m2.getName
      else if (m1.getDeclaringClass != m2.getDeclaringClass) {
        !m1.getDeclaringClass.isAssignableFrom(m2.getDeclaringClass)
      } else if (m1.getReturnType != m2.getReturnType) {
        !m1.getReturnType.isAssignableFrom(m2.getReturnType)
      } else {
        classSeqOrdering.lt(m1.getParameterTypes.toSeq, m2.getParameterTypes.toSeq)
      }
    )

    arr.distinctBy(_.getName)
  }

  def reflectNestedObjects0[T: ClassTag](
      outerCls: Class[?],
      filter: String => Boolean = Function.const(true),
      getMethods: (Class[?], Boolean, Class[?]) => Array[(java.lang.reflect.Method, String)]
  ): Array[(String, java.lang.reflect.Member)] = {

    val first = reflect(
      outerCls,
      summon[ClassTag[T]].runtimeClass,
      filter,
      noParams = true,
      getMethods
    )
      .map(m => (m.getName, m))

    val companionClassOpt = outerCls.getName match {
      // This case only happens when the modules are nested within a top-level static `object`,
      // which itself only happens for `ExternalModule`s. So only check in that
      // case to minimize the performance overhead since `Class.forName` is pretty slow
      case s"$prefix$$" if outerCls.getSuperclass == classOf[mill.api.ExternalModule] =>
        try Some(Class.forName(prefix))
        catch { case _: Throwable => None }
      case _ => None
    }
    val second = (Array(outerCls) ++ companionClassOpt)
      .flatMap(_.getClasses)
      .filter(summon[ClassTag[T]].runtimeClass.isAssignableFrom(_))
      .flatMap { c =>
        c.getName.stripPrefix(outerCls.getName) match {
          case s"$name$$" if filter(scala.reflect.NameTransformer.decode(name)) =>
            c.getFields.find(f => f.getName == "MODULE$").map(name -> _)
          case _ => None
        }

      }
      .distinct

    // Sometimes `getClasses` returns stuff in odd orders, make sure to sort for determinism
    second.sortInPlaceBy(_._1)

    first ++ second
  }

  def reflectNestedObjects02[T: ClassTag](
      outerCls: Class[?],
      filter: String => Boolean = Function.const(true),
      getMethods: (Class[?], Boolean, Class[?]) => Array[(java.lang.reflect.Method, String)]
  ): Array[(name: String, `class`: Class[?], getter: Any => T)] = {
    reflectNestedObjects0[T](outerCls, filter, getMethods).map {
      case (name, m: java.lang.reflect.Method) =>
        (name, m.getReturnType, (outer: Any) => m.invoke(outer).asInstanceOf[T])
      case (name, m: java.lang.reflect.Field) =>
        (name, m.getType, (outer: Any) => m.get(outer).asInstanceOf[T])
      case other => throw new IllegalArgumentException(s"Unexpected member: $other")
    }
  }

}
