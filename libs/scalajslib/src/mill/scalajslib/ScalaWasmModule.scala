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
 * It also generates Scala bindings from the WIT files in [[witDirectory]] (`wit/`),
 * with [[https://github.com/scala-wasm/wit-bindgen-scala wit-bindgen-scala]].
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

  /**
   * Directory with the WIT files (WebAssembly Component Model interfaces) to generate
   * Scala bindings for, with [[witBindgenExecutable]]. Bindings are generated only if
   * the directory exists.
   */
  def witDirectory: T[PathRef] = Task.Source("wit")

  /** The WIT world to generate bindings for (`--world`); `None` lets the generator pick it. */
  def witWorld: T[Option[String]] = Task { None }

  /** The base package of the generated bindings (`--base-package`); `None` uses the generator's default. */
  def witPackage: T[Option[String]] = Task { None }

  /**
   * The [[https://github.com/scala-wasm/wit-bindgen-scala wit-bindgen-scala]] executable,
   * looked up on the `PATH` unless it is an absolute path. Mill does not download it.
   */
  def witBindgenExecutable: T[String] = Task { "wit-bindgen-scala" }

  /** Extra arguments for [[witBindgenExecutable]], e.g. `--with` remappings or `--features`. */
  def witBindgenArgs: T[Seq[String]] = Task { Seq.empty[String] }

  /**
   * Scala bindings generated from [[witDirectory]] by [[witBindgenExecutable]].
   * They are part of [[generatedSources]].
   */
  def witBindings: T[Seq[PathRef]] = Task {
    val witDir = witDirectory().path
    if (!os.exists(witDir)) Seq.empty[PathRef]
    else {
      val outDir = Task.dest / "wit-bindgen"
      os.makeDir.all(outDir)
      os.proc(
        witBindgenExecutable(),
        witDir,
        "--out-dir",
        outDir,
        witWorld().toSeq.flatMap(w => Seq("--world", w)),
        witPackage().toSeq.flatMap(p => Seq("--base-package", p)),
        witBindgenArgs()
      ).call(cwd = Task.dest, stdout = os.Inherit)
      Seq(PathRef(outDir))
    }
  }

  override def generatedSources: T[Seq[PathRef]] = Task {
    super.generatedSources() ++ witBindings()
  }
}

/** A test module for a [[ScalaWasmModule]], see [[ScalaWasmModule.ScalaWasmTests]] */
trait TestScalaWasmModule extends ScalaWasmModule with TestScalaJSModule
