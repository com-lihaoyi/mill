package mill.integration
import utest._
object ExampleTestSuite extends IntegrationTestSuite{
  val tests = Tests {
    val workspaceRoot = initWorkspace()

    test("exampleUsage") {
      val usageComment =
        os.read.lines(workspaceRoot / "build.sc")
          .dropWhile(_ != "/* Example Usage")
          .drop(1)
          .takeWhile(_ != "*/")
          .mkString("\n")

      val commandBlocks = usageComment.trim.split("\n\n")

      for(commandBlock <- commandBlocks){
        val commandBlockLines = commandBlock.linesIterator.toVector
        println("ExampleTestSuite: " + commandBlockLines.head)
        commandBlockLines.head match{
          case s"> ./$command" =>

            val expectedSnippets = commandBlockLines.tail
            val evalResult = command match{
              case s"mill $rest" => evalStdout(rest.split(" "): _*)
              case rest =>
                val res = os.proc(rest.split(" ")).call(stdout=os.Pipe, stderr = os.Pipe, cwd = workspaceRoot)
                IntegrationTestSuite.EvalResult(res.exitCode == 0, res.out.text(), res.err.text())
            }

            if (expectedSnippets.exists(_.startsWith("error: "))) assert(!evalResult.isSuccess)
            else assert(evalResult.isSuccess)

            val unwrappedExpected = expectedSnippets.map{
              case s"error: $msg" => msg
              case msg => msg
            }

            for (expected <- unwrappedExpected) {
              if (integrationTestMode != "local") {
                println("ExampleTestSuite expected: " + expected)
                def plainText(s: String) =
                  fansi.Str(s, errorMode = fansi.ErrorMode.Strip).plainText

                assert(
                  plainText(evalResult.err).contains(expected) ||
                  plainText(evalResult.out).contains(expected)
                )
              }
            }
          case s"> cp -r $from $to" =>
            os.copy(os.Path(from, workspaceRoot), os.Path(to, workspaceRoot))
          case s"> sed -i 's/$oldStr/$newStr/g' $file" =>
            mangleFile(os.Path(file, workspaceRoot), _.replace(oldStr, newStr))

        }
      }
    }
  }
}
