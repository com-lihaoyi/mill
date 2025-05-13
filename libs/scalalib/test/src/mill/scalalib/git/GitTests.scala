package mill.scalalib.git

import mill.define.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object GitTests extends TestSuite {

  object gitModule extends TestRootModule, GitModule {
    lazy val millDiscover = Discover[this.type]
  }

  def tests = Tests {

    test("ratchet") {
      UnitTester(gitModule, os.temp.dir()).scoped { eval =>

        def call(cmd: os.Shellable*) =
          os.proc(cmd*).call(stderr = os.Pipe, cwd = gitModule.moduleDir)

        // init repo
        call("git", "init", ".")
        call("git", "remote", "add", "-f", "origin", "git@github.com:com-lihaoyi/os-lib.git")
        call("git", "checkout", "0.11.4")

        val Right(result0) = eval(gitModule.ratchet())
        assert(result0.value.isEmpty)

        val added = os.temp(dir = gitModule.moduleDir)
        val Right(result1) = eval(gitModule.ratchet())
        assert(
          result1.value.head.path == added,
          result1.value.length == 1
        )

        // https://github.com/com-lihaoyi/os-lib/compare/0.11.3...0.11.4
        val Right(result2) = eval(gitModule.ratchet("0.11.3", Some("0.11.4")))
        assert(
          result2.value.exists(_.path.endsWith(".github/workflows/publish-artifacts.yml")),
          result2.value.exists(_.path.endsWith("os/test/src/ZipOpTests.scala")),
          !result2.value.exists(_.path.endsWith("os/test/src-jvm/ExpectyIntegration.scala")),
          result2.value.length == 37 // 1 file deleted
        )
      }
    }
  }
}
