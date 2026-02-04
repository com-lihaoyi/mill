package mill.main.buildgen

import mill.main.buildgen.ModuleSpec.{Opt, Values}
import utest.*

object BuildGenYamlTests extends TestSuite {

  val tests = Tests {
    test("javacOptionsWithCommasAreQuotedInYaml") {
      val workspace = os.temp.dir()
      val rootModule = ModuleSpec(
        name = "example",
        javacOptions = Values(
          base = Seq(
            Opt("--release", "25"),
            Opt("-Xlint:all,-this-escape,-serial,-dangling-doc-comments")
          )
        )
      )

      BuildGenYaml.writeBuildFiles(
        workspace = workspace,
        packages = Seq(PackageSpec(os.sub, rootModule)),
        millVersion = "SNAPSHOT",
        millJvmVersion = "system"
      )

      val generated = os.read(workspace / "build.mill.yaml")
      assert(
        generated.contains(
          """javacOptions: [--release, 25, "-Xlint:all,-this-escape,-serial,-dangling-doc-comments"]"""
        )
      )
    }
  }
}
