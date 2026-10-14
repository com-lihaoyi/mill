package mill.scalajslib

import mill.*
import mill.scalajslib.api.*

/**
 * A Scala module compiled and linked to a stand-alone WebAssembly component
 * (WASI Component Model), for runtimes such as [[https://wasmtime.dev wasmtime]].
 *
 * Today this uses [[https://github.com/scala-wasm/scala-wasm scala-wasm]], a friendly
 * fork of Scala.js that is published under `io.github.scala-wasm`. The fork shares
 * the Scala.js toolchain, so this module shares the implementation of
 * [[ScalaJSModule]] and its `scalaJS*` settings. It only changes the defaults:
 *
 *  - [[scalaJSOrganization]] and [[scalaJSVersion]]: the scala-wasm toolchain
 *  - [[moduleKind]]: [[ModuleKind.WasmComponent]]
 *  - [[scalaJSUseWebAssembly]]: `true`
 *  - [[esFeatures]]: ES2022
 *
 * scala-wasm plans to upstream its work to Scala.js. When that happens, only these
 * defaults change, and modules that extend `ScalaWasmModule` keep working.
 *
 * {{{
 * object component extends ScalaWasmModule {
 *   def scalaVersion = "2.13.18"
 * }
 * }}}
 */
trait ScalaWasmModule extends ScalaJSModule {

  /**
   * Test module for a [[ScalaWasmModule]]. It uses the toolchain and the settings of
   * its parent module. [[ScalaJSTests]] comes last, so that its forwarders to the
   * parent win over the defaults of [[TestScalaWasmModule]].
   */
  trait ScalaWasmTests extends TestScalaWasmModule with ScalaJSTests

  /** The scala-wasm organization */
  override def scalaJSOrganization: T[String] = Task { "io.github.scala-wasm" }

  /** The scala-wasm version that this Mill version was tested with */
  override def scalaJSVersion: T[String] = Task { ScalaJSBuildInfo.scalaWasmVersion }

  override def moduleKind: T[ModuleKind] = Task { ModuleKind.WasmComponent }

  override def scalaJSUseWebAssembly: T[Boolean] = Task { true }

  override def esFeatures: T[ESFeatures] = Task {
    super.esFeatures().withESVersion(ESVersion.ES2022)
  }
}

/** A test module for a [[ScalaWasmModule]], see [[ScalaWasmModule.ScalaWasmTests]] */
trait TestScalaWasmModule extends ScalaWasmModule with TestScalaJSModule
