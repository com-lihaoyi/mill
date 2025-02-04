package mill.define

import java.util.StringTokenizer
import scala.collection.JavaConverters.*

object OverrideMapping {
  trait Wrapper {
    private[mill] def linearized: Seq[Class[_]]
  }

  def computeLinearization(cls: Class[_]): Seq[Class[_]] = {
    val seen = collection.mutable.Set[Class[_]]()

    def rec(cls: Class[_]): Seq[Class[_]] = {
      val parents = Option(cls.getSuperclass) ++ cls.getInterfaces
      parents.iterator.flatMap(rec(_)).toSeq ++ Option.when(seen.add(cls))(cls)
    }

    rec(cls)
  }


  def assignOverridenTaskSegments(overriddenEnclosings: Seq[String],
                                  taskMethodName: String,
                                  taskClassName: String) = {
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
      Seq(Segment.Label(taskMethodName + ".super")) ++ superSuffix.map(Segment.Label)
    )
  }

}
