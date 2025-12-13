package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object YamlConfigTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval(("run"))
      val outLines = res.out.linesIterator.toSeq

      // Ensure that all these YAML scalar values that look like various types have their
      // raw text perserved when parsed as strings in `forkEnv`, rather than being round-tripped
      // through their respective type causing loss of the original string representation.
      val expected = Seq(
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
        "NUM13" -> "-.NAN"
      )

      for ((k, v) <- expected) assert(outLines.contains(s"$k=$v"))

      // `null` is the only scalar parsed via `visitNull`, since `null` is a valid string.
      // This should cause it to be not included in the `forkEnv` passed to the subprocess,
      // and not printed in the output
      val expectedMissing = Seq(
        "NULL1"
      )
      for (k <- expectedMissing) assert(!outLines.exists(_.startsWith(s"$k=")))
    }
  }
}
