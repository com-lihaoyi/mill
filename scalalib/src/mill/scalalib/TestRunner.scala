package mill.scalalib
import mill.util.JsonFormatters._
object TestRunner {

  def framework(frameworkName: String)(cl: ClassLoader): sbt.testing.Framework  = {
    cl.loadClass(frameworkName)
      .newInstance()
      .asInstanceOf[sbt.testing.Framework]
  }

  case class Result(fullyQualifiedName: String,
                    selector: String,
                    duration: Long,
                    status: String,
                    exceptionName: Option[String],
                    exceptionMsg: Option[String],
                    exceptionTrace: Option[Seq[StackTraceElement]])

  object Result{
    implicit def resultRW: upickle.default.ReadWriter[Result] = upickle.default.macroRW[Result]
  }

}
