package mill.kotlinlib

import mill.javalib.PlatformModuleBase

/**
 * A [[KotlinModule]] intended for defining `.jvm`/`.js`/etc. submodules
 * It supports additional source directories per platform, e.g. `src-jvm/` or
 * `src-js/`.
 *
 * Adjusts the [[moduleDir]] and [[artifactNameParts]] to ignore the last
 * path segment, which is assumed to be the name of the platform the module is
 * built against and not something that should affect the filesystem path or
 * artifact name
 */
trait PlatformKotlinModule extends PlatformModuleBase with KotlinModule
