package mill.scalalib

/**
 * A [[ScalaModule]] intended for defining `.jvm`/`.js`/`.native` submodules
 * It supports additional source directories per platform, e.g. `src-jvm/` or
 * `src-js/` and can be used inside a [[CrossScalaModule.Base]], to get one
 * source folder per platform per version e.g. `src-2.12-jvm/`.
 *
 * Adjusts the [[moduleDir]] and [[artifactNameParts]] to ignore the last
 * path segment, which is assumed to be the name of the platform the module is
 * built against and not something that should affect the filesystem path or
 * artifact name
 */
trait PlatformScalaModule extends mill.javalib.PlatformModuleBase with ScalaModule
