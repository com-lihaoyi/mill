package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object CosmoTests extends UtestIntegrationTestSuite {
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
    }
  }
}
