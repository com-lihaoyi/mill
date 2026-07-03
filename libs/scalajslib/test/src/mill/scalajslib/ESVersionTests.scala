package mill.scalajslib

import mill.scalajslib.api.*
import utest.*

object ESVersionTests extends TestSuite {
  val tests: Tests = Tests {
    test("ESVersion enum contains ES2022") {
      assert(ESVersion.valueOf("ES2022") == ESVersion.ES2022)
    }
    test("ESVersion enum contains ES2023") {
      assert(ESVersion.valueOf("ES2023") == ESVersion.ES2023)
    }
    test("ESVersion enum contains ES2024") {
      assert(ESVersion.valueOf("ES2024") == ESVersion.ES2024)
    }
    test("ESVersion enum has all expected values") {
      val names = ESVersion.values.map(_.toString).toSet
      val expected = Set(
        "ES5_1",
        "ES2015",
        "ES2016",
        "ES2017",
        "ES2018",
        "ES2019",
        "ES2020",
        "ES2021",
        "ES2022",
        "ES2023",
        "ES2024"
      )
      assert(names == expected)
    }
    test("ESFeatures withESVersion accepts ES2022") {
      val features = ESFeatures.Defaults.withESVersion(ESVersion.ES2022)
      assert(features.esVersion == ESVersion.ES2022)
    }
    test("ESFeatures withESVersion accepts ES2023") {
      val features = ESFeatures.Defaults.withESVersion(ESVersion.ES2023)
      assert(features.esVersion == ESVersion.ES2023)
    }
    test("ESFeatures withESVersion accepts ES2024") {
      val features = ESFeatures.Defaults.withESVersion(ESVersion.ES2024)
      assert(features.esVersion == ESVersion.ES2024)
    }
  }
}
