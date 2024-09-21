package mill.initmodule

import mill.define.{Discover, ExternalModule}
import mill.{Command, Module, T}
import os.Path

import java.io.IOException
import scala.util.{Failure, Success, Try, Using}

object InitModule extends ExternalModule with InitModule {
  lazy val millDiscover: Discover = Discover[this.type]
}

trait InitModule extends Module {

  type ExampleUrl = String
  type ExampleId = String

  private def usingExamples[T](fun: Seq[(ExampleId, ExampleUrl)] => T): Try[T] =
    Using(getClass.getClassLoader.getResourceAsStream("exampleList.txt")) { exampleList =>
      val reader = upickle.default.reader[Seq[(ExampleId, ExampleUrl)]]
      val exampleNames: Seq[(ExampleId, ExampleUrl)] = upickle.default.read(exampleList)(reader)
      fun(exampleNames)
    }

  /**
   * @return Seq of example names or Seq with path to parent dir where downloaded example was unpacked
   */
  def init(@mainargs.arg(positional = true, short = 'e') exampleId: Option[ExampleId])
      : Command[Seq[String]] =
    T.command {
      usingExamples { examples =>
        val result: Try[(Seq[String], String)] = exampleId match {
          case None =>
            val exampleIds: Seq[ExampleId] = examples.map { case (exampleId, _) => exampleId }
            val msg =
              "Run init with one of the following examples as an argument to download and extract example:\n"
            val message = msg + exampleIds.mkString("\n")
            Success(exampleIds, message)
          case Some(value) =>
            val result: Try[(Seq[String], String)] = for {
              url <- examples.toMap.get(value).toRight(new Exception(
                s"Example [$value] is not present in examples list"
              )).toTry
              path <- Try(mill.util.Util.downloadUnpackZip(url, os.rel)(T.workspace)).recoverWith(
                _ => Failure(new IOException(s"Couldn't download example: [$value]"))
              )
              _ = Try(os.remove(T.workspace / "tmp.zip"))
            } yield (Seq(path.path.toString()), s"Example downloaded to [${path.path.toString}]")

            result
        }
        result
      }.flatten match {
        case Success((ret, msg)) =>
          T.log.outputStream.println(msg)
          ret
        case Failure(exception) =>
          T.log.error(exception.getMessage)
          throw exception
      }
    }
}
