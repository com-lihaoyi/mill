package mill.scalalib
import mill.util.JsonFormatters._
object TestRunner {

  def frameworks(frameworkNames: Seq[String])(cl: ClassLoader): Seq[sbt.testing.Framework] = {
    frameworkNames.map { name =>
      cl.loadClass(name).newInstance().asInstanceOf[sbt.testing.Framework]
    }
  }

  case class Result(fullyQualifiedName: String,
                    selector: String,
                    duration: Long,
                    status: String,
                    exceptionName: Option[String] = None,
                    exceptionMsg: Option[String] = None,
                    exceptionTrace: Option[Seq[StackTraceElement]] = None)

  object Result{
    implicit def resultRW: upickle.default.ReadWriter[Result] = upickle.default.macroRW[Result]
  }

}
