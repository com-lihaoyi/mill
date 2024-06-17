package mill.scalalib

import mill._
import mill.api.Result
import mill.eval.Evaluator
import mill.util.{Jvm, TestEvaluator, TestUtil}
import utest._
import utest.framework.TestPath

import java.io.PrintStream

// Ensure the assembly is runnable, even if we have assembled lots of dependencies into it
// Reproduction of issues:
// - https://github.com/com-lihaoyi/mill/issues/528
// - https://github.com/com-lihaoyi/mill/issues/2650

object AssemblyTests extends TestSuite {

  object TestCase extends TestUtil.BaseModule {
    trait Setup extends ScalaModule {
      def scalaVersion = "2.13.11"
      def sources = T.sources(T.workspace / "src")
      def ivyDeps = super.ivyDeps() ++ Agg(
        ivy"com.lihaoyi::scalatags:0.8.2",
        ivy"com.lihaoyi::mainargs:0.4.0",
        ivy"org.apache.avro:avro:1.11.1"
      )
    }
    trait ExtraDeps extends ScalaModule {
      def ivyDeps = super.ivyDeps() ++ Agg(
        ivy"dev.zio::zio:2.0.15",
        ivy"org.typelevel::cats-core:2.9.0",
        ivy"org.apache.spark::spark-core:3.4.0",
        ivy"dev.zio::zio-metrics-connectors:2.0.8",
        ivy"dev.zio::zio-http:3.0.0-RC2"
      )
    }

    object noExe extends Module {
      object small extends Setup {
        override def prependShellScript: T[String] = ""
      }
      object large extends Setup with ExtraDeps {
        override def prependShellScript: T[String] = ""
      }
    }

    object exe extends Module {
      object small extends Setup
      object large extends Setup with ExtraDeps
    }

  }

  val sources = Map(
    os.rel / "src" / "Main.scala" ->
      """package ultra
        |
        |import scalatags.Text.all._
        |import mainargs.{main, ParserForMethods}
        |
        |object Main {
        |  def generateHtml(text: String) = {
        |    h1(text).toString
        |  }
        |
        |  @main
        |  def main(text: String) = {
        |    println(generateHtml(text))
        |  }
        |
        |  def main(args: Array[String]): Unit = ParserForMethods(this).runOrExit(args)
        |}""".stripMargin
  )

  def workspaceTest[T](
      m: TestUtil.BaseModule,
      env: Map[String, String] = Evaluator.defaultEnv,
      debug: Boolean = false,
      errStream: PrintStream = System.err
  )(t: TestEvaluator => T)(implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m, env = env, debugEnabled = debug, errStream = errStream)
    os.remove.all(m.millSourcePath)
    sources.foreach { case (file, content) =>
      os.write(m.millSourcePath / file, content, createFolders = true)
    }
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    t(eval)
  }

  def runAssembly(file: os.Path, wd: os.Path, checkExe: Boolean = false): Unit = {
    println(s"File size: ${os.stat(file).size}")
    Jvm.runSubprocess(
      commandArgs = Seq(Jvm.javaExe, "-jar", file.toString(), "--text", "tutu"),
      envArgs = Map.empty[String, String],
      workingDir = wd
    )
    if (checkExe) {
      Jvm.runSubprocess(
        commandArgs = Seq(file.toString(), "--text", "tutu"),
        envArgs = Map.empty[String, String],
        workingDir = wd
      )
    }
  }

  def tests: Tests = Tests {
    test("Assembly") {
      test("noExe") {
        test("small") {
          workspaceTest(TestCase) { eval =>
            val Right((res, _)) = eval(TestCase.noExe.small.assembly)
            runAssembly(res.path, TestCase.millSourcePath)
          }
        }
        test("large") {
          workspaceTest(TestCase) { eval =>
            val Right((res, _)) = eval(TestCase.noExe.large.assembly)
            runAssembly(res.path, TestCase.millSourcePath)
          }
        }
      }
      test("exe") {
        test("small") {
          workspaceTest(TestCase) { eval =>
            val Right((res, _)) = eval(TestCase.exe.small.assembly)
            runAssembly(res.path, TestCase.millSourcePath, checkExe = true)
          }
        }
        test("large-should-fail") {
          workspaceTest(TestCase) { eval =>
            val Left(Result.Failure(msg, Some(res))) = eval(TestCase.exe.large.assembly)
            val expectedMsg =
              """The created assembly jar contains more than 65535 ZIP entries.
                |JARs of that size are known to not work correctly with a prepended shell script.
                |Either reduce the entries count of the assembly or disable the prepended shell script with:
                |
                |  def prependShellScript = ""
                |""".stripMargin
            assert(msg == expectedMsg)
          }
        }
      }
    }
  }
}
