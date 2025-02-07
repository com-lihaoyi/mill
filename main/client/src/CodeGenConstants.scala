package mill.main.client

/**
 * Constants used for code generation in Mill builds.
 */
object CodeGenConstants {

  /**
   * Global package prefix for Mill builds. Cannot be `build` because
   * it would conflict with the name of the `lazy val build` object
   * we import to work around the need for the `.package` suffix, so
   * we add an `_` and call it `build_`
   */
  val globalPackagePrefix: String = "build_"

  /**
   * What the wrapper objects are called. Not `package` because we don't
   * want them to be `package objects` due to weird edge case behavior,
   * even though we want them to look like package objects to users and IDEs,
   * so we add an `_` and call it `package_`
   */
  val wrapperObjectName: String = "package_"

  /**
   * The name of the root build file
   */
  val rootBuildFileNames: Array[String] = Array("build.mill", "build.mill.scala", "build.sc")

  /**
   * The name of any sub-folder build files
   */
  val nestedBuildFileNames: Array[String] =
    Array("package.mill", "package.mill.scala", "package.sc")

  /**
   * The extensions used by build files
   */
  val buildFileExtensions: Array[String] = Array("mill", "mill.scala", "sc")

  /**
   * The user-facing name for the root of the module tree.
   */
  val rootModuleAlias: String = "build"
}
