package mill.util

import utest.*

object ShellConfigurationTests extends TestSuite {

  private val dummyPath = os.pwd / "dummy"

  val tests: Tests = Tests {
    test("formatEnvVar") {
      test("bash escapes special characters") {
        val result =
          ShellConfiguration.Bash(dummyPath)
            .formatEnvVar("FOO", """hello "world" $HOME `cmd`!hist\n""")
        assert(result == """export FOO="hello \"world\" \$HOME \`cmd\`\!hist\\n"""")
      }
      test("zsh escapes special characters") {
        val result =
          ShellConfiguration.Zsh(dummyPath).formatEnvVar("FOO", """a"b$c`d!e\f""")
        assert(result == """export FOO="a\"b\$c\`d\!e\\f"""")
      }
      test("fish escapes special characters") {
        val result =
          ShellConfiguration.Fish(dummyPath).formatEnvVar("FOO", """a"b$c!d\e""")
        assert(result == """set -gx FOO "a\"b\$c\!d\\e"""")
      }
      test("nushell escapes special characters") {
        val result =
          ShellConfiguration.Nushell(dummyPath).formatEnvVar("FOO", """a"b'c\d""")
        assert(result == """$env.FOO = "a\"b\'c\\d"""")
      }
      test("powershell escapes special characters") {
        val result =
          ShellConfiguration.PowerShell(dummyPath).formatEnvVar("FOO", """a"b`c""")
        assert(result == """$env:FOO = "a`"b``c"""")
      }
      test("newlines are escaped") {
        val result =
          ShellConfiguration.Bash(dummyPath).formatEnvVar("FOO", "line1\nline2")
        assert(result == """export FOO="line1\nline2"""")
      }
      test("powershell newlines use backtick-n") {
        val result =
          ShellConfiguration.PowerShell(dummyPath).formatEnvVar("FOO", "line1\nline2")
        assert(result == """$env:FOO = "line1`nline2"""")
      }
    }

    test("envVarPattern") {
      test("bash matches quoted export line") {
        val pattern = ShellConfiguration.Bash(dummyPath).envVarPattern("MY_VAR")
        assert(pattern.findFirstIn("""export MY_VAR="hello"""").isDefined)
        assert(pattern.findFirstIn("""export OTHER_VAR="hello"""").isEmpty)
      }
      test("bash does not match unquoted values") {
        val pattern = ShellConfiguration.Bash(dummyPath).envVarPattern("MY_VAR")
        assert(pattern.findFirstIn("""export MY_VAR=hello""").isEmpty)
      }
      test("regex injection is prevented") {
        val pattern = ShellConfiguration.Bash(dummyPath).envVarPattern("FOO.*BAR")
        assert(pattern.findFirstIn("""export FOO.*BAR="val"""").isDefined)
        assert(pattern.findFirstIn("""export FOOXXBAR="val"""").isEmpty)
      }
      test("fish matches set line") {
        val pattern = ShellConfiguration.Fish(dummyPath).envVarPattern("MY_VAR")
        assert(pattern.findFirstIn("""set -gx MY_VAR "hello"""").isDefined)
        assert(pattern.findFirstIn("""set -gx OTHER "hello"""").isEmpty)
      }
      test("nushell matches env line") {
        val pattern = ShellConfiguration.Nushell(dummyPath).envVarPattern("MY_VAR")
        assert(pattern.findFirstIn("""$env.MY_VAR = "hello"""").isDefined)
      }
      test("nushell does not match across lines") {
        val pattern = ShellConfiguration.Nushell(dummyPath).envVarPattern("MY_VAR")
        val multiline = "$env.MY_VAR = \"hello\nworld\""
        assert(pattern.findFirstIn(multiline).isEmpty)
      }
      test("powershell matches env line") {
        val pattern = ShellConfiguration.PowerShell(dummyPath).envVarPattern("MY_VAR")
        assert(pattern.findFirstIn("""$env:MY_VAR = "hello"""").isDefined)
      }
      test("bash matches values with escaped quotes") {
        val pattern = ShellConfiguration.Bash(dummyPath).envVarPattern("MY_VAR")
        assert(pattern.findFirstIn("""export MY_VAR="hello \"world\""""").isDefined)
      }
    }

    test("upsertEnvVar") {
      test("inserts new variable") {
        val tmp = os.temp("# existing config\n")
        ShellConfiguration.Bash(tmp).upsertEnvVar("NEW_VAR", "new_value")
        val content = os.read(tmp)
        assert(content.contains("""export NEW_VAR="new_value""""))
        assert(content.startsWith("# existing config\n"))
      }
      test("updates existing variable") {
        val tmp = os.temp("# config\nexport MY_VAR=\"old\"\n# end\n")
        ShellConfiguration.Bash(tmp).upsertEnvVar("MY_VAR", "new")
        val content = os.read(tmp)
        assert(content.contains("""export MY_VAR="new""""))
        assert(!content.contains("old"))
        assert(content.contains("# end"))
      }
      test("updates variable with escaped quotes in existing value") {
        val tmp = os.temp("# config\nexport MY_VAR=\"hello \\\"world\\\"\"\n# end\n")
        ShellConfiguration.Bash(tmp).upsertEnvVar("MY_VAR", "new")
        val content = os.read(tmp)
        assert(content.contains("""export MY_VAR="new""""))
        assert(!content.contains("world"))
        assert(content.contains("# end"))
      }
      test("handles file without trailing newline") {
        val tmp = os.temp("# config")
        ShellConfiguration.Bash(tmp).upsertEnvVar("VAR", "val")
        val content = os.read(tmp)
        assert(content.contains("""export VAR="val""""))
        assert(content.startsWith("# config\n"))
      }
    }
  }
}
