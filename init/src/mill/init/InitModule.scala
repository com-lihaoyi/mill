package mill.init

import mill.define.{Discover, ExternalModule}
import mill.{Command, T, Module}

import java.io.IOException
import scala.util.{Failure, Try, Using}


object InitModule extends ExternalModule with InitModule{
  lazy val millDiscover: Discover = Discover[this.type]
}

trait InitModule extends Module{
  def init(@mainargs.arg(positional = true, short = 'e') example: Option[String]): Command[Unit] = T.command {
    Using(getClass.getClassLoader.getResourceAsStream("exampleList.txt")) { exampleList =>
      val reader = upickle.default.reader[Seq[(String, String)]]
      val exampleNames: Seq[(String, String)] = upickle.default.read(exampleList)(reader)

      example match {
        case None =>
          val msg = "Run init with one of the following examples as an argument to download and extract example:\n"
          val examplesMsgPart = exampleNames.map { case (path, _) => path }.mkString("\n")
          Try(T.log.info(msg + examplesMsgPart))
        case Some(value) =>
          for {
            url <- exampleNames.toMap.get(value).toRight(new Exception(s"Example [$value] is not present in examples list")).toTry
            path <- Try(mill.util.Util.downloadUnpackZip(url, os.rel)(T.workspace)).recoverWith(_ => Failure(new IOException(s"Couldn't download example: [$value]")))
            _ = Try(os.remove(T.workspace / "tmp.zip"))
          } yield T.log.info("Example downloaded to " + path)

      }


    }.flatten.recover { case ex => T.log.error(ex.getMessage) }.get
  }
}
