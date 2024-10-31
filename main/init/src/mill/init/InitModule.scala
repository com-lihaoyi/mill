package mill.init

import mainargs.{Flag, arg}
import mill.define.{Discover, ExternalModule}
import mill.{Command, Module, T}

import scala.util.{Failure, Success, Try, Using}

object InitModule extends ExternalModule with InitModule {
  lazy val millDiscover: Discover = Discover[this.type]
}

trait InitModule extends Module {

  type ExampleUrl = String
  type ExampleId = String

  val msg: String =
    """Run `mill init <example-id>` with one of these examples as an argument to download and extract example.
      |Run `mill init --show-all` to see full list of examples.
      |Run `mill init <Giter8 template>` to generate project from Giter8 template.""".stripMargin
  def moduleNotExistMsg(id: String): String = s"Example [$id] is not present in examples list"
  def directoryExistsMsg(extractionTargetPath: String): String =
    s"Can't download example, because extraction directory [$extractionTargetPath] already exist"

  /**
   * @return Seq of example names or Seq with path to parent dir where downloaded example was unpacked
   */
  def init(
      @mainargs.arg(positional = true, short = 'e') exampleId: Option[ExampleId],
      @arg(name = "show-all") showAll: Flag = Flag()
  ): Command[Seq[String]] =
    T.command {
      usingExamples { examples =>
        exampleId match {
          case None =>
            val exampleIds: Seq[ExampleId] = examples.map { case (exampleId, _) => exampleId }
            def fullMessage(exampleIds: Seq[ExampleId]) =
              msg + "\n\n" + exampleIds.mkString("\n") + "\n\n" + msg
            if (showAll.value) (exampleIds, fullMessage(exampleIds))
            else {
              val toShow = List("basic", "builds", "web")
              val filteredIds = exampleIds
                .filter(_.split('/').lift.apply(1).exists(toShow.contains))

              (filteredIds, fullMessage(filteredIds))
            }

          case Some(value) =>
            val url = examples.toMap.get(value).getOrElse(sys.error(moduleNotExistMsg(value)))
            println(s"Downloading example from $url...")
            val zipName = url.split('/').last
            val extractedDirName = zipName.stripSuffix(".zip")
            val downloaded = os.temp(requests.get(url))
            println(s"Unpacking example...")
            val unpackPath = os.unzip(downloaded, T.dest)
            val extractedPath = T.dest / extractedDirName
            val conflicting = for {
              p <- os.walk(extractedPath)
              rel = p.relativeTo(extractedPath)
              if os.exists(T.workspace / rel)
            } yield rel

            if (conflicting.nonEmpty) {
              throw new Exception(
                "Unable to unpack example because it conflicts with existing file: " +
                  conflicting.mkString(", ")
              )
            }

            for (p <- os.walk(extractedPath)) {
              println(p.relativeTo(extractedPath))
            }

            // Remove any existing bootstrap script since the example will come with one
            os.remove(T.workspace / "mill")
            os.copy.apply(extractedPath, T.workspace, mergeFolders = true)

            // Make sure the `./mill` launcher is executable
            os.perms.set(T.workspace / "mill", "rwxrwxrwx")

            (
              Seq(unpackPath.toString()),
              s"Example download and unpacked to [${T.workspace}]; " +
                "See `build.mill` for an explanation of this example and instructions on how to use it"
            )
        }
      } match {
        case Success((ret, msg)) =>
          T.log.outputStream.println(msg)
          ret
        case Failure(exception) =>
          T.log.error(exception.getMessage)
          throw exception
      }
    }
  private def usingExamples[T](fun: Seq[(ExampleId, ExampleUrl)] => T): Try[T] =
    Using(getClass.getClassLoader.getResourceAsStream("exampleList.txt")) { exampleList =>
      val reader = upickle.default.reader[Seq[(ExampleId, ExampleUrl)]]
      val exampleNames: Seq[(ExampleId, ExampleUrl)] =
        upickle.default.read(exampleList)(using reader)
      fun(exampleNames)
    }
}
