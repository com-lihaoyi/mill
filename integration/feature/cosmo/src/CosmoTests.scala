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
    test("test Cosmopolitan APE assemblies") - integrationTest {
      tester =>
        import tester._

        // test running a simple hello world APE assembly
        val res0 = eval("helloworld.cosmoAssembly")
        assert(res0.isSuccess)
        val assembly0 = workspacePath / "out/helloworld/cosmoAssembly.dest/out.jar.exe"
        assert(os.call(assembly0).out.text().trim == "Hello World")

        // test running an APE assembly with arguments
        val res1 = eval("hello.cosmoAssembly")
        assert(res1.isSuccess)
        val assembly1 = workspacePath / "out/hello/cosmoAssembly.dest/out.jar.exe"
        val args = "scala".toSeq
        assert(os.call((assembly1, args)).out.text().trim == s"Hello ${args.mkString(" ")}")

        // test running an APE assembly with forkArgs
        val res2 = eval("javaopts.cosmoAssembly")
        assert(res2.isSuccess)
        val assembly2 = workspacePath / "out/javaopts/cosmoAssembly.dest/out.jar.exe"

        val forkArgs = "my.java.property" -> "hello"
        val forkArgsArgv0 = "my.argv0" -> assembly2.toString

        val props = os.call(assembly2).out.lines()
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

        val props0 = os.call(assembly2, env = updatedEnv, propagateEnv = false).out.lines()
          .map(_.split('='))
          .collect {
            case Array(k, v) => k -> v
          }

        assert(props0.contains("my.jvm.property" -> "0"))
    }
  }
}
