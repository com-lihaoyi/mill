package mill.internal

import utest.*

object MillCliConfigTests extends TestSuite {
  val tests: Tests = Tests {
    // Checks that `--help-advanced` shows the name of every advanced flag, including flags
    // declared with an explicit `name` such as `--notify-watch`.
    test("helpAdvancedNamesEveryFlag") {
      val text = MillCliConfig.helpAdvancedUsageText
      val advancedArgs = MillCliConfig.parser.main.argSigs0
        .filter(a => a.hidden && !MillCliConfig.isUnsupported(a))
      for (a <- advancedArgs; name <- a.longName(mainargs.Util.kebabCaseNameMapper))
        assert(text.contains(s"--$name "))
    }
  }
}
