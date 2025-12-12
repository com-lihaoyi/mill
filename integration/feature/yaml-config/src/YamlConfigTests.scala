package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object YamlConfigTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val expected = Seq(
        "NULL1" -> "null",
        "NULL2" -> "Null",
        "NULL3" -> "NULL",
        "NULL4" -> "~",
        "TRUE1" -> "y",
        "TRUE2" -> "Y",
        "TRUE3" -> "yes",
        "TRUE4" -> "Yes",
        "TRUE5" -> "YES",
        "TRUE6" -> "true",
        "TRUE7" -> "True",
        "TRUE8" -> "TRUE",
        "TRUE9" -> "on",
        "TRUE10" -> "On",
        "TRUE11" -> "ON",
        "FALSE1" -> "n",
        "FALSE2" -> "N",
        "FALSE3" -> "no",
        "FALSE4" -> "No",
        "FALSE5" -> "NO",
        "FALSE6" -> "false",
        "FALSE7" -> "False",
        "FALSE8" -> "FALSE",
        "FALSE9" -> "off",
        "FALSE10" -> "Off",
        "FALSE11" -> "off",
        "NUM1" -> "1.200",
        "NUM2" -> ".5",
        "NUM3" -> "0.5",
        "NUM4" -> "2.",
        "NUM5" -> "1e3",
        "NUM6" -> "-2E-2",
        "NUM7" -> ".inf",
        "NUM8" -> ".Inf",
        "NUM9" -> ".INF",
        "NUM10" -> "-.INF",
        "NUM11" -> "-.nan",
        "NUM12" -> "-.Nan",
        "NUM13" -> "-.NAN",
      )
      val res = tester.eval(("run"))
      val outLines = res.out.linesIterator.toSeq
      for((k, v) <- expected) assert(outLines.contains(s"$k=$v"))
    }
  }
}
