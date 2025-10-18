package mill.contrib.scalapblib

import upickle.ReadWriter

/**
 * A ScalaPB generator
 * @param generator The CLI option to enable the generator
 * @param supportsScalaPbOptions `true` if the [[ScalaPBModule.scalaPBOptions]] should be used to read options.
 */
enum Generator(val generator: String, val supportsScalaPbOptions: Boolean) derives ReadWriter {
  /** Generate Scala code. */
  case ScalaGen extends Generator("--scala_out", true)
  /** Generate Java code. */
  case JavaGen extends Generator("--java_out", false)
  /** Can be used to set up a custom generator. */
  case CustomGen(override val generator: String, override val supportsScalaPbOptions: Boolean)
      extends Generator(generator, supportsScalaPbOptions)
}
