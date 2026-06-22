package mill.scalajslib.config

import mill.scalajslib.worker.api.*
import utest.*

object ScalaJSConfigVersionGuardTests extends TestSuite {
  private def makeESFeatures(esVersion: ESVersion, useJSPI: Boolean = false): ESFeatures =
    ESFeatures(
      allowBigIntsForLongs = false,
      avoidClasses = true,
      avoidLetsAndConsts = true,
      esVersion = esVersion,
      useJSPI = useJSPI
    )

  val tests: Tests = Tests {
    test("minorIsGreaterThanOrEqual") {
      test("1.17.0 >= 17 is true") {
        assert(ScalaJSConfig.minorIsGreaterThanOrEqual("1.17.0", 17))
      }
      test("1.16.0 >= 17 is false") {
        assert(!ScalaJSConfig.minorIsGreaterThanOrEqual("1.16.0", 17))
      }
      test("1.22.0 >= 22 is true") {
        assert(ScalaJSConfig.minorIsGreaterThanOrEqual("1.22.0", 22))
      }
      test("1.21.0 >= 22 is false") {
        assert(!ScalaJSConfig.minorIsGreaterThanOrEqual("1.21.0", 22))
      }
      test("1.6.0 >= 6 is true") {
        assert(ScalaJSConfig.minorIsGreaterThanOrEqual("1.6.0", 6))
      }
      test("1.5.0 >= 6 is false") {
        assert(!ScalaJSConfig.minorIsGreaterThanOrEqual("1.5.0", 6))
      }
    }

    test("ES2022 with Scala.js 1.21 throws version guard") {
      val ex = intercept[Exception] {
        ScalaJSConfig.config(
          sjsVersion = "1.21.0",
          moduleSplitStyle = ModuleSplitStyle.FewestModules,
          esFeatures = makeESFeatures(ESVersion.ES2022),
          moduleKind = ModuleKind.NoModule,
          scalaJSOptimizer = true,
          scalaJSSourceMap = true,
          patterns = OutputPatterns.Defaults,
          useWebAssembly = false
        )
      }
      assert(ex.getMessage.contains("ES2022"))
      assert(ex.getMessage.contains("1.22"))
    }

    test("ES2023 with Scala.js 1.21 throws version guard") {
      val ex = intercept[Exception] {
        ScalaJSConfig.config(
          sjsVersion = "1.21.0",
          moduleSplitStyle = ModuleSplitStyle.FewestModules,
          esFeatures = makeESFeatures(ESVersion.ES2023),
          moduleKind = ModuleKind.NoModule,
          scalaJSOptimizer = true,
          scalaJSSourceMap = true,
          patterns = OutputPatterns.Defaults,
          useWebAssembly = false
        )
      }
      assert(ex.getMessage.contains("ES2023"))
      assert(ex.getMessage.contains("1.22"))
    }

    test("ES2024 with Scala.js 1.21 throws version guard") {
      val ex = intercept[Exception] {
        ScalaJSConfig.config(
          sjsVersion = "1.21.0",
          moduleSplitStyle = ModuleSplitStyle.FewestModules,
          esFeatures = makeESFeatures(ESVersion.ES2024),
          moduleKind = ModuleKind.NoModule,
          scalaJSOptimizer = true,
          scalaJSSourceMap = true,
          patterns = OutputPatterns.Defaults,
          useWebAssembly = false
        )
      }
      assert(ex.getMessage.contains("ES2024"))
      assert(ex.getMessage.contains("1.22"))
    }

    test("ES2021 with Scala.js 1.21 succeeds") {
      ScalaJSConfig.config(
        sjsVersion = "1.21.0",
        moduleSplitStyle = ModuleSplitStyle.FewestModules,
        esFeatures = makeESFeatures(ESVersion.ES2021),
        moduleKind = ModuleKind.NoModule,
        scalaJSOptimizer = true,
        scalaJSSourceMap = true,
        patterns = OutputPatterns.Defaults,
        useWebAssembly = false
      )
    }

    test("JSPI with WASM on Scala.js 1.21 throws") {
      val ex = intercept[Exception] {
        ScalaJSConfig.config(
          sjsVersion = "1.21.0",
          moduleSplitStyle = ModuleSplitStyle.FewestModules,
          esFeatures = makeESFeatures(ESVersion.ES2021, useJSPI = true),
          moduleKind = ModuleKind.ESModule,
          scalaJSOptimizer = true,
          scalaJSSourceMap = true,
          patterns = OutputPatterns.Defaults,
          useWebAssembly = true
        )
      }
      assert(ex.getMessage.contains("JSPI"))
      assert(ex.getMessage.contains("1.22"))
    }

    test("JSPI without WASM does not throw") {
      ScalaJSConfig.config(
        sjsVersion = "1.21.0",
        moduleSplitStyle = ModuleSplitStyle.FewestModules,
        esFeatures = makeESFeatures(ESVersion.ES2021, useJSPI = true),
        moduleKind = ModuleKind.ESModule,
        scalaJSOptimizer = true,
        scalaJSSourceMap = true,
        patterns = OutputPatterns.Defaults,
        useWebAssembly = false
      )
    }

    test("ES5_1 with Scala.js 1.5 succeeds") {
      ScalaJSConfig.config(
        sjsVersion = "1.5.0",
        moduleSplitStyle = ModuleSplitStyle.FewestModules,
        esFeatures = makeESFeatures(ESVersion.ES5_1),
        moduleKind = ModuleKind.NoModule,
        scalaJSOptimizer = true,
        scalaJSSourceMap = true,
        patterns = OutputPatterns.Defaults,
        useWebAssembly = false
      )
    }

    test("ES2016 with Scala.js 1.5 throws") {
      val ex = intercept[Exception] {
        ScalaJSConfig.config(
          sjsVersion = "1.5.0",
          moduleSplitStyle = ModuleSplitStyle.FewestModules,
          esFeatures = makeESFeatures(ESVersion.ES2016),
          moduleKind = ModuleKind.NoModule,
          scalaJSOptimizer = true,
          scalaJSSourceMap = true,
          patterns = OutputPatterns.Defaults,
          useWebAssembly = false
        )
      }
      assert(ex.getMessage.contains("1.6"))
    }
  }
}
