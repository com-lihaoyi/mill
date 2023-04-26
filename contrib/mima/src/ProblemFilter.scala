package mill.mima

import scala.annotation.nowarn
import scala.reflect.ClassTag
import scala.reflect.classTag

class ProblemFilter private (val name: String, val problem: String)
object ProblemFilter {
  def exclude[P <: ProblemRef: ClassTag](name: String): ProblemFilter =
    new ProblemFilter(
      name = name,
      problem = classTag[P].runtimeClass.getSimpleName()
    )

  implicit val problemFilterRW: upickle.default.ReadWriter[ProblemFilter] =
    upickle.default.macroRW[ProblemFilter]

  @nowarn("msg=private method apply in object ProblemFilter is never used")
  private def apply(name: String, problem: String) =
    new ProblemFilter(name = name, problem = problem)
}
