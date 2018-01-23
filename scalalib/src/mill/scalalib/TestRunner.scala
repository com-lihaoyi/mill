package mill.scalalib
import mill.util.JsonFormatters._
object TestRunner {


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
