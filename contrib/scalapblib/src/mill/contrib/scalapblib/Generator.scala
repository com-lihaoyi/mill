package mill.contrib.scalapblib

import upickle.ReadWriter

/**
 * A ScalaPB generator
 * @param generator The CLI option to enable the generator
 * @param supportsScalaPbOptions `true` if the [[ScalaPBModule.scalaPBOptions]] should be used to read options.
 */
enum Generator(val generator: String, val supportsScalaPbOptions: Boolean) derives ReadWriter {
  case ScalaGen extends Generator("--scala_out", true)
  case JavaGen extends Generator("--java_out", false)
  case CustomGen(override val generator: String, override val supportsScalaPbOptions: Boolean)
      extends Generator(generator, supportsScalaPbOptions)
}
