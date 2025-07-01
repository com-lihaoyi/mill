package mill.init

import mainargs.{Flag, arg}
import mill.api.{Discover, ExternalModule}
import mill.api.BuildCtx
import mill.{Command, Module, Task}

import scala.util.{Failure, Success, Try, Using}

object InitModule extends ExternalModule with InitModule {
  lazy val millDiscover = Discover[this.type]
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
    Task.Command {
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
            val url = examples.toMap.getOrElse(value, sys.error(moduleNotExistMsg(value)))
            println(s"Downloading example from $url...")
            val zipName = url.split('/').last
            val extractedDirName = zipName.stripSuffix(".zip")
            // TODO: use a previously downloaded index when offline
            if (Task.offline) sys.error("Can't fetch a list of examples in offline Mode.")
            val downloaded = os.temp(requests.get(url))
            println(s"Unpacking example...")
            val unpackPath = os.unzip(downloaded, Task.dest)
            val extractedPath = Task.dest / extractedDirName
            val conflicting = for {
              p <- os.walk(extractedPath)
              rel = p.relativeTo(extractedPath)
              if os.exists(BuildCtx.workspaceRoot / rel)
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
            os.remove(BuildCtx.workspaceRoot / "mill")
            os.copy.apply(extractedPath, BuildCtx.workspaceRoot, mergeFolders = true)

            // Make sure the `./mill` launcher is executable
            // Skip on windows since windows doesn't support POSIX permissions
            if (!scala.util.Properties.isWin) {
              os.perms.set(BuildCtx.workspaceRoot / "mill", "rwxrwxrwx")
            }

            (
              Seq(unpackPath.toString()),
              s"Example download and unpacked to [${BuildCtx.workspaceRoot}]; " +
                "See `build.mill` for an explanation of this example and instructions on how to use it"
            )
        }
      } match {
        case Success((ret, msg)) =>
          Task.log.streams.out.println(msg)
          ret
        case Failure(exception) =>
          Task.log.error(exception.getMessage)
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
