package mill.util

import utest.*

object ShellConfigurationTests extends TestSuite {
  val tests: Tests = Tests {
    test("formatEnvVar") {
      test("bash escapes special characters") {
        val result =
          ShellConfiguration.Bash.formatEnvVar("FOO", """hello "world" $HOME `cmd`!hist\n""")
        assert(result == """export FOO="hello \"world\" \$HOME \`cmd\`\!hist\\n"""")
      }
      test("zsh escapes special characters") {
        val result = ShellConfiguration.Zsh.formatEnvVar("FOO", """a"b$c`d!e\f""")
        assert(result == """export FOO="a\"b\$c\`d\!e\\f"""")
      }
      test("fish escapes special characters") {
        val result = ShellConfiguration.Fish.formatEnvVar("FOO", """a"b$c!d\e""")
        assert(result == """set -gx FOO "a\"b\$c\!d\\e"""")
      }
      test("nushell escapes special characters") {
        val result = ShellConfiguration.Nushell.formatEnvVar("FOO", """a"b'c\d""")
        assert(result == """$env.FOO = "a\"b\'c\\d"""")
      }
      test("powershell escapes special characters") {
        val result = ShellConfiguration.PowerShell.formatEnvVar("FOO", """a"b`c""")
        assert(result == """$env:FOO = "a`"b``c"""")
      }
      test("newlines are escaped") {
        val result = ShellConfiguration.Bash.formatEnvVar("FOO", "line1\nline2")
        assert(result == """export FOO="line1\nline2"""")
      }
      test("powershell newlines use backtick-n") {
        val result = ShellConfiguration.PowerShell.formatEnvVar("FOO", "line1\nline2")
        assert(result == """$env:FOO = "line1`nline2"""")
      }
    }

    test("envVarPattern") {
      test("bash matches quoted export line") {
        val pattern = ShellConfiguration.Bash.envVarPattern("MY_VAR")
        assert(pattern.findFirstIn("""export MY_VAR="hello"""").isDefined)
        assert(pattern.findFirstIn("""export OTHER_VAR="hello"""").isEmpty)
      }
      test("bash does not match unquoted values") {
        val pattern = ShellConfiguration.Bash.envVarPattern("MY_VAR")
        assert(pattern.findFirstIn("""export MY_VAR=hello""").isEmpty)
      }
      test("regex injection is prevented") {
        val pattern = ShellConfiguration.Bash.envVarPattern("FOO.*BAR")
        assert(pattern.findFirstIn("""export FOO.*BAR="val"""").isDefined)
        assert(pattern.findFirstIn("""export FOOXXBAR="val"""").isEmpty)
      }
      test("fish matches set line") {
        val pattern = ShellConfiguration.Fish.envVarPattern("MY_VAR")
        assert(pattern.findFirstIn("""set -gx MY_VAR "hello"""").isDefined)
        assert(pattern.findFirstIn("""set -gx OTHER "hello"""").isEmpty)
      }
      test("nushell matches env line") {
        val pattern = ShellConfiguration.Nushell.envVarPattern("MY_VAR")
        assert(pattern.findFirstIn("""$env.MY_VAR = "hello"""").isDefined)
      }
      test("nushell does not match across lines") {
        val pattern = ShellConfiguration.Nushell.envVarPattern("MY_VAR")
        val multiline = "$env.MY_VAR = \"hello\nworld\""
        assert(pattern.findFirstIn(multiline).isEmpty)
      }
      test("powershell matches env line") {
        val pattern = ShellConfiguration.PowerShell.envVarPattern("MY_VAR")
        assert(pattern.findFirstIn("""$env:MY_VAR = "hello"""").isDefined)
      }
    }

    test("upsertEnvVar") {
      test("inserts new variable") {
        val tmp = os.temp("# existing config\n")
        ShellConfiguration.Bash.upsertEnvVar(tmp, "NEW_VAR", "new_value")
        val content = os.read(tmp)
        assert(content.contains("""export NEW_VAR="new_value""""))
        assert(content.startsWith("# existing config\n"))
      }
      test("updates existing variable") {
        val tmp = os.temp("# config\nexport MY_VAR=\"old\"\n# end\n")
        ShellConfiguration.Bash.upsertEnvVar(tmp, "MY_VAR", "new")
        val content = os.read(tmp)
        assert(content.contains("""export MY_VAR="new""""))
        assert(!content.contains("old"))
        assert(content.contains("# end"))
      }
      test("handles file without trailing newline") {
        val tmp = os.temp("# config")
        ShellConfiguration.Bash.upsertEnvVar(tmp, "VAR", "val")
        val content = os.read(tmp)
        assert(content.contains("""export VAR="val""""))
        assert(content.startsWith("# config\n"))
      }
    }

    test("binaryNames") {
      test("powershell has multiple binary names") {
        assert(ShellConfiguration.PowerShell.binaryNames == Seq("pwsh", "powershell"))
      }
      test("bash has single binary name") {
        assert(ShellConfiguration.Bash.binaryNames == Seq("bash"))
      }
    }
  }
}
