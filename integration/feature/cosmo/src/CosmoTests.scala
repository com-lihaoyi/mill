package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

import scala.jdk.CollectionConverters._

object CosmoTests extends UtestIntegrationTestSuite {
  def updateEnv(env: Map[String, String], k: String, v: String): Map[String, String] = {
    env.get(k).fold(env.updated(k, v)) { v0 =>
      env.updated(k, s"$v0 $v")
    }
  }

  val tests: Tests = Tests {
    test("test running an APE assembly with arguments") - integrationTest {
      tester =>
        if (!scala.util.Properties.isWin) {
          import tester._

          val res = eval("hello.cosmoAssembly")
          assert(res.isSuccess)
          val assembly = workspacePath / "out/hello/cosmoAssembly.dest/out.jar.exe"
          val args = "scala".toSeq
          assert(os.call((assembly, args)).out.text().trim == s"Hello ${args.mkString(" ")}")
        }
    }

    test("test running an APE assembly with forkArgs and JavaOpts") - integrationTest {
      tester =>
        if (!scala.util.Properties.isWin) {
          import tester._

          val res = eval("javaopts.cosmoAssembly")
          assert(res.isSuccess)
          val assembly = workspacePath / "out/javaopts/cosmoAssembly.dest/out.jar.exe"

          val forkArgs = "my.java.property" -> "hello"
          val forkArgsArgv0 = "my.argv0" -> assembly.toString

          val props = os.call(assembly).out.lines()
            .map(_.split('='))
            .collect {
              case Array(k, v) => k -> v
            }

          assert(props.contains(forkArgs))
          assert(props.contains(forkArgsArgv0))

          val vars = Map(
            "JAVA_OPTS" -> "-Dmy.jvm.property=0",
            "JDK_JAVA_OPTIONS" -> "-Dmy.jvm.property=1",
            "JAVA_TOOL_OPTIONS" -> "-Dmy.jvm.property=2"
          )

          val updatedEnv = vars.foldRight(System.getenv().asScala.toMap) { case ((k, v), env) =>
            updateEnv(env, k, v)
          }

          val props0 = os.call(assembly, env = updatedEnv, propagateEnv = false).out.lines()
            .map(_.split('='))
            .collect {
              case Array(k, v) => k -> v
            }

          assert(props0.contains("my.jvm.property" -> "0"))
        }
    }
  }
}
