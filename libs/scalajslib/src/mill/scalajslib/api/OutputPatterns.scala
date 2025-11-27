package mill.scalajslib.api

import mill.api.internal.Mirrors
import upickle.{ReadWriter => RW, macroRW}
import mill.api.internal.Mirrors.autoMirror

class OutputPatterns private (
    val jsFile: String,
    val sourceMapFile: String,
    val moduleName: String,
    val jsFileURI: String,
    val sourceMapURI: String
) {

  /** Pattern for the JS file name (the file containing the module's code). */
  def withJSFile(jsFile: String): OutputPatterns =
    copy(jsFile = jsFile)

  /** Pattern for the file name of the source map file of the JS file. */
  def withSourceMapFile(sourceMapFile: String): OutputPatterns =
    copy(sourceMapFile = sourceMapFile)

  /** Pattern for the module name (the string used to import a module). */
  def withModuleName(moduleName: String): OutputPatterns =
    copy(moduleName = moduleName)

  /** Pattern for the "file" field in the source map. */
  def withJSFileURI(jsFileURI: String): OutputPatterns =
    copy(jsFileURI = jsFileURI)

  /** Pattern for the source map URI in the JS file. */
  def withSourceMapURI(sourceMapURI: String): OutputPatterns =
    copy(sourceMapURI = sourceMapURI)

  override def toString(): String = {
    s"""OutputPatterns(
       |  jsFile        = $jsFile,
       |  sourceMapFile = $sourceMapFile,
       |  moduleName    = $moduleName,
       |  jsFileURI     = $jsFileURI,
       |  sourceMapURI  = $sourceMapURI,
       |)""".stripMargin
  }

  private def copy(
      jsFile: String = jsFile,
      sourceMapFile: String = sourceMapFile,
      moduleName: String = moduleName,
      jsFileURI: String = jsFileURI,
      sourceMapURI: String = sourceMapURI
  ): OutputPatterns = {
    OutputPatterns(jsFile, sourceMapFile, moduleName, jsFileURI, sourceMapURI)
  }
}

object OutputPatterns {

  /** Default [[OutputPatterns]]; equivalent to `fromJSFile("%s.js")`. */
  val Defaults: OutputPatterns = fromJSFile("%s.js")

  /**
   * Creates [[OutputPatterns]] from a JS file pattern.
   *
   *  Other patterns are derived from the JS file pattern as follows:
   *  - `sourceMapFile`: ".map" is appended.
   *  - `moduleName`: "./" is prepended (relative path import).
   *  - `jsFileURI`: relative URI (same as the provided pattern).
   *  - `sourceMapURI`: relative URI (same as `sourceMapFile`).
   */
  def fromJSFile(jsFile: String): OutputPatterns = {
    OutputPatterns(
      jsFile = jsFile,
      sourceMapFile = s"$jsFile.map",
      moduleName = s"./$jsFile",
      jsFileURI = jsFile,
      sourceMapURI = s"$jsFile.map"
    )
  }

  // scalafix:off; we want to hide the generic apply method
  private def apply(
      jsFile: String,
      sourceMapFile: String,
      moduleName: String,
      jsFileURI: String,
      sourceMapURI: String
  ): OutputPatterns = OutputPatterns(
    jsFile,
    sourceMapFile,
    moduleName,
    jsFileURI,
    sourceMapURI
  )
  // scalalfix:on

  implicit val rw: RW[OutputPatterns] = macroRW[OutputPatterns]

  private given Root_OutputPatterns: Mirrors.Root[OutputPatterns] =
    Mirrors.autoRoot[OutputPatterns]
}
