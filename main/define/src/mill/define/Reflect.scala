package mill.define

import fastparse.NoWhitespace._
import fastparse._

import java.lang.reflect.Modifier
import scala.reflect.ClassTag
import scala.reflect.NameTransformer.decode

private[mill] object Reflect {

  def ident[_p: P]: P[String] = P(CharsWhileIn("a-zA-Z0-9_\\-")).!

  def standaloneIdent[_p: P]: P[String] = P(Start ~ ident ~ End)

  def isLegalIdentifier(identifier: String): Boolean =
    parse(identifier, standaloneIdent(_)).isInstanceOf[Parsed.Success[_]]

  def reflect(
      outer: Class[_],
      inner: Class[_],
      filter: String => Boolean,
      noParams: Boolean
  ): Seq[java.lang.reflect.Method] = {
    val res = for {
      m <- outer.getMethods
      n = decode(m.getName)
      if filter(n) &&
        isLegalIdentifier(n) &&
        (!noParams || m.getParameterCount == 0) &&
        (m.getModifiers & Modifier.STATIC) == 0 &&
        (m.getModifiers & Modifier.ABSTRACT) == 0 &&
        inner.isAssignableFrom(m.getReturnType)
    } yield m

    if (outer.toString.contains("BarModule")) pprint.log(inner)
    if (outer.toString.contains("BarModule")) pprint.log(res)
    // There can be multiple methods of the same name on a class if a sub-class
    // overrides a super-class method and narrows the return type. Make sure we
    // sort the methods by their declaring class from lowest to highest in the
    // the type hierarchy, and use `distinctBy` to only keep the lowest
    // version, before we finally sort them by name
    res
      .sortWith((m1, m2) => m1.getDeclaringClass.isAssignableFrom(m2.getDeclaringClass))
      .reverse
      .distinctBy(_.getName)
      .sortBy(_.getName)
  }

  // For some reason, this fails to pick up concrete `object`s nested directly within
  // another top-level concrete `object`. This is fine for now, since Mill's Ammonite
  // script/REPL runner always wraps user code in a wrapper object/trait
  def reflectNestedObjects0[T: ClassTag](
      outerCls: Class[_],
      filter: String => Boolean = Function.const(true)
  ): Seq[(String, java.lang.reflect.Member)] = {

    if (outerCls.toString.contains("BarModule")) pprint.log(outerCls)
    val first = reflect(
      outerCls,
      implicitly[ClassTag[T]].runtimeClass,
      filter,
      noParams = true
    )
      .map(m => (m.getName, m))

    if (outerCls.toString.contains("BarModule")) pprint.log(first)
    if (outerCls.toString.contains("BarModule")) pprint.log(outerCls.getClasses)
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

    if (outerCls.toString.contains("BarModule")) pprint.log(second)
    first ++ second
  }
}
