package mill.initmodule

import mill.define.{Discover, ExternalModule}
import mill.{Command, Module, T}

import java.io.IOException
import scala.util.{Failure, Success, Try, Using}


object InitModule extends ExternalModule with InitModule{
  lazy val millDiscover: Discover = Discover[this.type]
}

trait InitModule extends Module{
  def init(@mainargs.arg(positional = true, short = 'e') example: Option[String]): Command[String] = T.command {
    Using(getClass.getClassLoader.getResourceAsStream("exampleList.txt")) { exampleList =>
      val reader = upickle.default.reader[Seq[(String, String)]]
      val exampleNames: Seq[(String, String)] = upickle.default.read(exampleList)(reader)

      val result:Try[String] = example match {
        case None =>
          val msg = "Run init with one of the following examples as an argument to download and extract example:\n"
          val examplesMsgPart = exampleNames.map { case (path, _) => path }.mkString("\n")
          val out = msg + examplesMsgPart
          Success(out)
        case Some(value) =>
          for {
            url <- exampleNames.toMap.get(value).toRight(new Exception(s"Example [$value] is not present in examples list")).toTry
            path <- Try(mill.util.Util.downloadUnpackZip(url, os.rel)(T.workspace)).recoverWith(_ => Failure(new IOException(s"Couldn't download example: [$value]")))
            _ = Try(os.remove(T.workspace / "tmp.zip"))
          } yield "Example downloaded to " + path

      }
      result
    }.flatten match {
      case Success(value) =>
        T.log.outputStream.println(value)
        value
      case Failure(exception) =>
        T.log.error(exception.getMessage)
        throw exception
    }
  }
}
