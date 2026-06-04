package mill.main.buildgen

import mill.main.buildgen.ModuleSpec.{Opt, Values}
import utest.*

object BuildGenYamlTests extends TestSuite {

  val tests = Tests {
    test("yamlEscapingTests") {
      val specialStrings = Seq(
        // strings with commas
        "-Xlint:all,-this-escape,-serial",
        // string with placeholder
        "${maven.home}",
        // strings starting with special characters
        "{brace",
        "[bracket",
        "*asterisk",
        "&ampersand",
        "!exclamation",
        "|pipe",
        ">greater"
      )

      val workspace = os.temp.dir()
      val rootModule = ModuleSpec(
        name = "example",
        javacOptions = Values(
          base = specialStrings.map(s => Opt(s))
        )
      )

      BuildGenYaml.writeBuildFiles(
        baseDir = workspace,
        packages = Seq(PackageSpec(os.sub, rootModule))
      )

      val generated = os.read(workspace / "build.mill.yaml")

      for (s <- specialStrings) {
        val expected = s"\"$s\""
        assert(generated.contains(expected))
      }
    }
  }
}
