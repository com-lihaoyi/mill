package mill.scalajslib.config

import mill.scalajslib.worker.api.*
import org.scalajs.linker.{interface => sjs}

private[mill] object ScalaJSConfig {

  private[config] def minorIsGreaterThanOrEqual(sjsVersion: String, number: Int) =
    sjsVersion match {
      case s"1.$n.$_" if n.toIntOption.exists(_ < number) => false
      case _ => true
    }

  /** Mill's default Scala.js linker configuration for the passed parameters */
  def config(
      sjsVersion: String,
      moduleSplitStyle: ModuleSplitStyle,
      esFeatures: ESFeatures,
      moduleKind: ModuleKind,
      scalaJSOptimizer: Boolean,
      scalaJSSourceMap: Boolean,
      patterns: OutputPatterns,
      useWebAssembly: Boolean,
      useWebAssemblyJSPI: Boolean
  ): sjs.StandardConfig = {

    val moduleSplitStyle0 = moduleSplitStyle match {
      case ModuleSplitStyle.FewestModules => sjs.ModuleSplitStyle.FewestModules
      case ModuleSplitStyle.SmallestModules => sjs.ModuleSplitStyle.SmallestModules
      case ModuleSplitStyle.SmallModulesFor(packages*) =>
        sjs.ModuleSplitStyle.SmallModulesFor(packages.toList)
    }
    val esFeatures0 = sjs.ESFeatures.Defaults
      .withAllowBigIntsForLongs(esFeatures.allowBigIntsForLongs)
      .withAvoidClasses(esFeatures.avoidClasses)
      .withAvoidLetsAndConsts(esFeatures.avoidLetsAndConsts)
      .withESVersion(
        esFeatures.esVersion match {
          case ESVersion.ES5_1 => sjs.ESVersion.ES5_1
          case ESVersion.ES2015 => sjs.ESVersion.ES2015
          case ESVersion.ES2016 => sjs.ESVersion.ES2016
          case ESVersion.ES2017 => sjs.ESVersion.ES2017
          case ESVersion.ES2018 => sjs.ESVersion.ES2018
          case ESVersion.ES2019 => sjs.ESVersion.ES2019
          case ESVersion.ES2020 => sjs.ESVersion.ES2020
          case ESVersion.ES2021 => sjs.ESVersion.ES2021
          case ESVersion.ES2022 => sjs.ESVersion.ES2022
          case ESVersion.ES2023 => sjs.ESVersion.ES2023
          case ESVersion.ES2024 => sjs.ESVersion.ES2024
        }
      )

    if (!minorIsGreaterThanOrEqual(sjsVersion, 3))
      moduleSplitStyle0 match {
        case sjs.ModuleSplitStyle.FewestModules =>
        case v => throw new Exception(
            s"ModuleSplitStyle $v is not supported with Scala.js < 1.2. Either update Scala.js or use ModuleSplitStyle.FewestModules"
          )
      }

    if (!minorIsGreaterThanOrEqual(sjsVersion, 6))
      esFeatures.esVersion match {
        case ESVersion.ES5_1 | ESVersion.ES2015 =>
        case v => throw new Exception(
            s"ESVersion $v is not supported with Scala.js < 1.6. Either update Scala.js or use one of ESVersion.ES5_1 or ESVersion.ES2015"
          )
      }

    if (!minorIsGreaterThanOrEqual(sjsVersion, 22))
      esFeatures.esVersion match {
        case ESVersion.ES2022 | ESVersion.ES2023 | ESVersion.ES2024 =>
          throw new Exception(
            s"ESVersion ${esFeatures.esVersion} is not supported with Scala.js < 1.22. Either update Scala.js or use ESVersion.ES2021 or earlier"
          )
        case _ =>
      }

    val moduleKind0 = moduleKind match {
      case ModuleKind.NoModule => sjs.ModuleKind.NoModule
      case ModuleKind.CommonJSModule => sjs.ModuleKind.CommonJSModule
      case ModuleKind.ESModule => sjs.ModuleKind.ESModule
    }

    var config = sjs.StandardConfig()
      .withSemantics(sjs.Semantics.Defaults)
      .withOptimizer(scalaJSOptimizer)
      .withSourceMap(scalaJSSourceMap)
      .withModuleKind(moduleKind0)
      .withESFeatures(esFeatures0)
      .withModuleSplitStyle(moduleSplitStyle0)

    if (minorIsGreaterThanOrEqual(sjsVersion, 3))
      config = config.withOutputPatterns(
        sjs.OutputPatterns.Defaults
          .withJSFile(patterns.jsFile)
          .withSourceMapFile(patterns.sourceMapFile)
          .withModuleName(patterns.moduleName)
          .withJSFileURI(patterns.jsFileURI)
          .withSourceMapURI(patterns.sourceMapURI)
      )

    if (useWebAssemblyJSPI && !useWebAssembly)
      throw Exception(
        "scalaJSUseWebAssemblyJSPI requires the WebAssembly backend to be enabled (set scalaJSUseWebAssembly = true)"
      )

    if (useWebAssembly)
      if (minorIsGreaterThanOrEqual(sjsVersion, 22)) {
        // Scala.js 1.22.0 stabilized WebAssembly support:
        // withExperimentalUseWebAssembly was replaced by ESFeatures.withUseWebAssembly
        val wasmEsFeatures = esFeatures0.withUseWebAssembly(true)
        config = config.withESFeatures(wasmEsFeatures)
        if (useWebAssemblyJSPI)
          config = config.withWasmFeatures(_.withUseJSPI(true))
      } else if (minorIsGreaterThanOrEqual(sjsVersion, 17)) {
        config = config.withExperimentalUseWebAssembly(true)
        if (useWebAssemblyJSPI)
          throw Exception(
            "JSPI (JavaScript Promise Integration) requires Scala.js >= 1.22.0"
          )
      } else
        throw Exception("Emitting wasm is not supported with Scala.js < 1.17")

    config
  }

}
