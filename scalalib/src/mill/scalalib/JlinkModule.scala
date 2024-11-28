package mill
package scalalib

import mill._

/**
 * Support building modular runtime images with the `jlink` tool, which is included in JDK 9 and later.
 *
 * The official `jlink` docs: https://docs.oracle.com/en/java/javase/23/docs/specs/man/jlink.html
 */
trait JlinkModule extends JavaModule {

  /** The base name for the runtime image */
  def jlinkImageName: T[String] = T { "jlink" }

  /** Name of the main module to be included in the runtime image */
  def jlinkModuleName: T[String] = T { "" }

  /** The main module's version number. */
  def jlinkModuleVersion: T[Option[String]] = T { None }

  /** The main class to use as the runtime entry point. */
  def jlinkMainClass: T[String] = T { finalMainClass() }

  /**
   * Compress level for the runtime image.
   * Valid values range between:
   *  "zip-0" (no compression) and "zip-9" (best compression)
   * Defaults to "zip-6"
   */
  def jlinkCompressLevel: T[String] = T { "zip-6" }

  /**
   * Creates a Java module file (.jmod) from compiled classes
   */
  def jmodPackage: T[PathRef] = T {

    val mainClass: String = finalMainClass()
    val outputPath = T.dest / "jlink.jmod"

    val libs = T.dest / "libs"
    val cp = runClasspath().map(_.path)
    val jars = cp.filter(os.exists).zipWithIndex.map { case (p, idx) =>
      val dest = libs / s"${p.last}"
      os.copy(p, dest, createFolders = true)
      dest
    }

    val classPath = jars.map(_.toString).mkString(sys.props("path.separator"))
    val args = {
      val baseArgs = Seq(
        "jmod",
        "create",
        "--class-path",
        classPath.toString,
        "--main-class",
        mainClass,
        "--module-path",
        classPath.toString,
        outputPath.toString
      )

      val versionArgs = jlinkModuleVersion().toSeq.flatMap { version =>
        Seq("--module-version", version)
      }

      baseArgs ++ versionArgs
    }
    os.proc(args).call()

    PathRef(outputPath)
  }

  /** Builds a custom runtime image using jlink */
  def jlinkAppImage: T[PathRef] = T {
    val modulePath = jmodPackage().path.toString
    val outputPath = T.dest / "jlink-runtime"

    val args = Seq(
      "jlink",
      "--launcher",
      s"${jlinkImageName()}=${jlinkModuleName()}/${jlinkMainClass()}",
      "--module-path",
      modulePath,
      "--add-modules",
      jlinkModuleName(),
      "--output",
      outputPath.toString,
      "--compress",
      jlinkCompressLevel().toString,
      "--no-header-files",
      "--no-man-pages"
    )
    os.proc(args).call()

    PathRef(outputPath)
  }
}
