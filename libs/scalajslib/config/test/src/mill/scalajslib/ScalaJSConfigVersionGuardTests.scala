package mill.scalajslib.config

import mill.scalajslib.worker.api.*
import utest.*

object ScalaJSConfigVersionGuardTests extends TestSuite {
  private def makeESFeatures(esVersion: ESVersion): ESFeatures =
    ESFeatures(
      allowBigIntsForLongs = false,
      avoidClasses = true,
      avoidLetsAndConsts = true,
      esVersion = esVersion
    )

  private def makeConfig(
      sjsVersion: String,
      esFeatures: ESFeatures,
      moduleKind: ModuleKind = ModuleKind.NoModule,
      useWebAssembly: Boolean = false,
      useWebAssemblyJSPI: Boolean = false,
      experimentalUseCustomDescriptors: Boolean = false
  ) =
    ScalaJSConfig.config(
      sjsVersion = sjsVersion,
      moduleSplitStyle = ModuleSplitStyle.FewestModules,
      esFeatures = esFeatures,
      moduleKind = moduleKind,
      scalaJSOptimizer = true,
      scalaJSSourceMap = true,
      patterns = OutputPatterns(
        jsFile = "%s.js",
        sourceMapFile = "%s.js.map",
        moduleName = "./%s.js",
        jsFileURI = "%s.js",
        sourceMapURI = "%s.js.map"
      ),
      useWebAssembly = useWebAssembly,
      useWebAssemblyJSPI = useWebAssemblyJSPI,
      experimentalUseCustomDescriptors = experimentalUseCustomDescriptors
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
      val ex = assertThrows[Exception] {
        makeConfig(
          sjsVersion = "1.21.0",
          esFeatures = makeESFeatures(ESVersion.ES2022)
        )
      }
      assert(ex.getMessage.contains("ES2022"))
      assert(ex.getMessage.contains("1.22"))
    }

    test("ES2023 with Scala.js 1.21 throws version guard") {
      val ex = assertThrows[Exception] {
        makeConfig(
          sjsVersion = "1.21.0",
          esFeatures = makeESFeatures(ESVersion.ES2023)
        )
      }
      assert(ex.getMessage.contains("ES2023"))
      assert(ex.getMessage.contains("1.22"))
    }

    test("ES2024 with Scala.js 1.21 throws version guard") {
      val ex = assertThrows[Exception] {
        makeConfig(
          sjsVersion = "1.21.0",
          esFeatures = makeESFeatures(ESVersion.ES2024)
        )
      }
      assert(ex.getMessage.contains("ES2024"))
      assert(ex.getMessage.contains("1.22"))
    }

    test("ES2021 with Scala.js 1.21 succeeds") {
      makeConfig(
        sjsVersion = "1.21.0",
        esFeatures = makeESFeatures(ESVersion.ES2021)
      )
    }

    test("JSPI with WASM on Scala.js 1.21 throws") {
      val ex = assertThrows[Exception] {
        makeConfig(
          sjsVersion = "1.21.0",
          esFeatures = makeESFeatures(ESVersion.ES2021),
          moduleKind = ModuleKind.ESModule,
          useWebAssembly = true,
          useWebAssemblyJSPI = true
        )
      }
      assert(ex.getMessage.contains("JSPI"))
      assert(ex.getMessage.contains("1.22"))
    }

    test("JSPI without WASM throws") {
      val ex = assertThrows[Exception] {
        makeConfig(
          sjsVersion = "1.21.0",
          esFeatures = makeESFeatures(ESVersion.ES2021),
          moduleKind = ModuleKind.ESModule,
          useWebAssembly = false,
          useWebAssemblyJSPI = true
        )
      }
      assert(ex.getMessage.contains("scalaJSUseWebAssemblyJSPI"))
      assert(ex.getMessage.contains("WebAssembly"))
    }

    test("JSPI with WASM on Scala.js 1.22 succeeds") {
      val config = makeConfig(
        sjsVersion = "1.22.0",
        esFeatures = makeESFeatures(ESVersion.ES2022),
        moduleKind = ModuleKind.ESModule,
        useWebAssembly = true,
        useWebAssemblyJSPI = true
      )
      assert(config.wasmFeatures.useJSPI)
    }

    test("WasmModule requires Scala.js 1.23") {
      val ex = assertThrows[Exception] {
        makeConfig("1.22.0", makeESFeatures(ESVersion.ES2015), ModuleKind.WasmModule)
      }
      assert(ex.getMessage.contains("ModuleKind.WasmModule requires Scala.js >= 1.23.0"))
    }

    test("WasmModule needs neither the ES backend switch nor ES2022") {
      val config = makeConfig("1.23.0", makeESFeatures(ESVersion.ES2015), ModuleKind.WasmModule)
      assert(config.moduleKind == org.scalajs.linker.interface.ModuleKind.WasmModule)
      assert(!config.esFeatures.useWebAssembly)
      assert(!config.wasmFeatures.experimentalUseCustomDescriptors)
      assert(!ScalaJSConfigModule.fullOptConfig(config).closureCompilerIfAvailable)
    }

    test("Custom Descriptors require Scala.js 1.23") {
      val ex = assertThrows[Exception] {
        makeConfig(
          "1.22.0",
          makeESFeatures(ESVersion.ES2022),
          ModuleKind.ESModule,
          useWebAssembly = true,
          experimentalUseCustomDescriptors = true
        )
      }
      assert(ex.getMessage.contains(
        "scalaJSExperimentalUseCustomDescriptors requires Scala.js >= 1.23.0"
      ))
    }

    test("Custom Descriptors require Wasm") {
      val ex = assertThrows[Exception] {
        makeConfig(
          "1.23.0",
          makeESFeatures(ESVersion.ES2015),
          experimentalUseCustomDescriptors = true
        )
      }
      assert(ex.getMessage.contains("requires the WebAssembly backend"))
    }

    test("Custom Descriptors and JSPI are independent") {
      for (
        moduleKind <- Seq(ModuleKind.ESModule, ModuleKind.WasmModule);
        jspi <- Seq(false, true); descriptors <- Seq(false, true)
      ) {
        val config = makeConfig(
          "1.23.0",
          makeESFeatures(ESVersion.ES2022),
          moduleKind,
          useWebAssembly = moduleKind == ModuleKind.ESModule,
          useWebAssemblyJSPI = jspi,
          experimentalUseCustomDescriptors = descriptors
        )
        assert(config.wasmFeatures.useJSPI == jspi)
        assert(config.wasmFeatures.experimentalUseCustomDescriptors == descriptors)
      }
    }

    test("WasmModule is rejected by JavaScript runner") {
      val report = mill.scalajslib.api.Report(
        Seq(mill.scalajslib.api.Report.Module(
          "main",
          "main.wasm",
          None,
          mill.scalajslib.api.ModuleKind.WasmModule
        )),
        mill.PathRef(os.pwd)
      )
      val ex = assertThrows[Exception] { ScalaJSConfigModule.jsEnvInputs(report) }
      assert(ex.getMessage.contains(ScalaJSConfig.wasmModuleRunError))
    }

    test("ES5_1 with Scala.js 1.5 succeeds") {
      makeConfig(
        sjsVersion = "1.5.0",
        esFeatures = makeESFeatures(ESVersion.ES5_1)
      )
    }

    test("ES2016 with Scala.js 1.5 throws") {
      val ex = assertThrows[Exception] {
        makeConfig(
          sjsVersion = "1.5.0",
          esFeatures = makeESFeatures(ESVersion.ES2016)
        )
      }
      assert(ex.getMessage.contains("1.6"))
    }
  }
}
