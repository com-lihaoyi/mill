package mill.scalajslib.config

import mill.scalajslib.worker.api.*
import org.scalajs.linker.{interface => sjs}

private[mill] object ScalaJSConfig {

  private[config] def minorIsGreaterThanOrEqual(sjsVersion: String, number: Int) =
    sjsVersion match {
      case s"1.$n.$_" if n.toIntOption.exists(_ < number) => false
      case _ => true
    }

  private final val WasmComponentClassName =
    "org.scalajs.linker.interface.ModuleKind$WasmComponent$"

  /**
   * `ModuleKind.WasmComponent` only exists in Scala.js linkers that implement the
   * WebAssembly Component Model, such as the scala-wasm fork
   * (https://github.com/scala-wasm/scala-wasm). Mill is compiled against upstream
   * Scala.js, so it is looked up reflectively in the linker-interface on the
   * worker classpath.
   */
  private[mill] def wasmComponentModuleKind(sjsVersion: String): sjs.ModuleKind =
    try {
      Class
        .forName(WasmComponentClassName, true, classOf[sjs.ModuleKind].getClassLoader)
        .getField("MODULE$")
        .get(null)
        .asInstanceOf[sjs.ModuleKind]
    } catch {
      case _: ClassNotFoundException =>
        throw Exception(
          s"ModuleKind.WasmComponent is not supported by Scala.js $sjsVersion. " +
            "It requires a Scala.js linker that implements the WebAssembly Component Model, " +
            "such as scala-wasm (scalaJSOrganization = \"io.github.scala-wasm\")."
        )
    }

  /** Whether `moduleKind` is the `ModuleKind.WasmComponent` of a Component Model linker. */
  private[mill] def isWasmComponent(moduleKind: sjs.ModuleKind): Boolean =
    moduleKind.getClass.getName == WasmComponentClassName

  private final val WasmModuleClassName =
    "org.scalajs.linker.interface.ModuleKind$WasmModule$"

  // Referencing the new singleton directly can make the JVM resolve its class
  // before the version guard, even on an older linker classpath.
  private def wasmModuleKind(sjsVersion: String): sjs.ModuleKind = {
    if (!minorIsGreaterThanOrEqual(sjsVersion, 23))
      throw Exception("ModuleKind.WasmModule requires Scala.js >= 1.23.0")
    Class.forName(WasmModuleClassName, true, classOf[sjs.ModuleKind].getClassLoader)
      .getField("MODULE$")
      .get(null)
      .asInstanceOf[sjs.ModuleKind]
  }

  private[mill] def isWasmModule(moduleKind: sjs.ModuleKind): Boolean =
    moduleKind.getClass.getName == WasmModuleClassName

  private[mill] val wasmModuleRunError =
    "Running or testing a ModuleKind.WasmModule module is not supported by Mill's " +
      "JavaScript environments. Link it with fastLinkJS or fullLinkJS and load the .wasm " +
      "file in a suitable WebAssembly host."

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
      useWebAssemblyJSPI: Boolean,
      experimentalUseCustomDescriptors: Boolean
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
      case ModuleKind.WasmModule => wasmModuleKind(sjsVersion)
      case ModuleKind.WasmComponent =>
        if (!useWebAssembly)
          throw Exception(
            "ModuleKind.WasmComponent requires the WebAssembly backend to be enabled (set scalaJSUseWebAssembly = true)"
          )
        wasmComponentModuleKind(sjsVersion)
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

    val targetsWebAssembly = useWebAssembly || moduleKind == ModuleKind.WasmModule

    if (useWebAssemblyJSPI && !targetsWebAssembly)
      throw Exception(
        "scalaJSUseWebAssemblyJSPI requires the WebAssembly backend to be enabled (set scalaJSUseWebAssembly = true)"
      )

    if (useWebAssembly)
      if (minorIsGreaterThanOrEqual(sjsVersion, 22)) {
        // Scala.js 1.22.0 stabilized WebAssembly support:
        // withExperimentalUseWebAssembly was replaced by ESFeatures.withUseWebAssembly
        val wasmEsFeatures = esFeatures0.withUseWebAssembly(true)
        config = config.withESFeatures(wasmEsFeatures)
      } else if (minorIsGreaterThanOrEqual(sjsVersion, 17)) {
        config = config.withExperimentalUseWebAssembly(true)
        if (useWebAssemblyJSPI)
          throw Exception(
            "JSPI (JavaScript Promise Integration) requires Scala.js >= 1.22.0"
          )
      } else
        throw Exception("Emitting wasm is not supported with Scala.js < 1.17")

    if (useWebAssemblyJSPI && minorIsGreaterThanOrEqual(sjsVersion, 22))
      config = config.withWasmFeatures(_.withUseJSPI(true))

    if (experimentalUseCustomDescriptors) {
      if (!minorIsGreaterThanOrEqual(sjsVersion, 23))
        throw Exception("scalaJSExperimentalUseCustomDescriptors requires Scala.js >= 1.23.0")
      if (!targetsWebAssembly)
        throw Exception(
          "scalaJSExperimentalUseCustomDescriptors requires the WebAssembly backend " +
            "(set scalaJSUseWebAssembly = true or moduleKind = ModuleKind.WasmModule)"
        )
      config = config.withWasmFeatures(_.withExperimentalUseCustomDescriptors(true))
    }

    config
  }

}
