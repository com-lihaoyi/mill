package mill.api.internal

import mill.api.{Discover, Segment, Segments}

import java.util.StringTokenizer
import scala.jdk.CollectionConverters.*

/**
 * Logic around mapping overridden tasks to `Segments` suffixes.
 *
 * If a task is overridden, only the final override is assigned the `foo`
 * segment, while the overridden tasks are assigned `foo.super/Bar` `foo.super/Qux`
 * etc.. The suffix is as short as possible for conciseness while still being distinct.
 *
 * To detect groups of overrides, we use the task names captured in the `Discover` macro.
 * This allows us to properly identify which `def` is the "final" one, which is difficult
 * to do by other means (e.g. JVM bytecode has synthetic forwarders that confuse things)
 */
private[mill] object OverrideMapping {
  def computeSegments(
      enclosingValue: mill.api.ModuleCtx.Wrapper,
      discover: Discover,
      lastSegmentStr: String,
      enclosingClassValue: Class[?]
  ) = {
    Option(enclosingValue) match {
      case Some(value) =>
        val linearized = value.moduleLinearized
        val declaring = linearized.filter(cls =>
          discover.classInfo.get(cls).exists(_.declaredTaskNameSet.contains(lastSegmentStr))
        )

        if (declaring.isEmpty || declaring.lastOption.contains(enclosingClassValue)) None
        else Some(assignOverridenTaskSegments(
          declaring.map(_.getName),
          lastSegmentStr,
          enclosingClassValue.getName
        ))
      case _ => None
    }
  }

  def computeLinearization(cls: Class[?]): Seq[Class[?]] = {
    // Manually reproduce the linearization order described in
    //
    // https://stackoverflow.com/questions/34242536/linearization-order-in-scala
    val seen = collection.mutable.Set[Class[?]]()

    def rec(cls: Class[?]): Seq[Class[?]] = {
      val parents = Option(cls.getSuperclass) ++ cls.getInterfaces
      parents.iterator.flatMap(rec(_)).toSeq ++ Option.when(seen.add(cls))(cls)
    }

    rec(cls)
  }

  def assignOverridenTaskSegments(
      overriddenEnclosings: Seq[String],
      taskMethodName: String,
      taskClassName: String
  ) = {
    // StringTokenizer is faster than String#split due to not using regexes
    def splitEnclosing(s: String) = new StringTokenizer(s, ".# $")
      .asIterator()
      .asScala.map(_.asInstanceOf[String])
      .filter(_ != "<empty>")
      .toArray

    val superSegmentStrings = overriddenEnclosings.map(splitEnclosing)

    // Find out how many segments of the enclosing strings are identical
    // among all overriden tasks, so we can drop them
    val shortestSuperLength = superSegmentStrings.map(_.length).min
    val dropLeft = Range(0, shortestSuperLength)
      .find(i => superSegmentStrings.distinctBy(_(i)).size != 1)
      .getOrElse(shortestSuperLength)

    val splitted = splitEnclosing(taskClassName)
    // `dropRight(1)` to always drop the task name, which has to be
    // the same for all overriden tasks with the same segments
    val superSuffix0 = splitted.drop(dropLeft)

    // If there are no different segments between the enclosing strings,
    // preserve at least one path segment which is the class name
    val superSuffix =
      if (superSuffix0.nonEmpty) superSuffix0.toSeq
      else Seq(splitted.last)
    Segments(
      Seq(Segment.Label(taskMethodName + ".super")) ++ superSuffix.map(Segment.Label(_))
    )
  }

}
