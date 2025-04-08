package mill
package scalalib

import mill._
import mill.util.Jvm

/**
 * Support for building a native package / installer with the `jpackage` tool which comes bundled with JDK 14 and later.
 *
 * The official `jpackage` docs: https://docs.oracle.com/en/java/javase/23/docs/specs/man/jpackage.html
 */
trait JpackageModule extends JavaModule {

  /** The application name */
  def jpackageName: T[String] = Task { artifactName() }

  /** The main class to use as the entry point to the native package / installer. */
  def jpackageMainClass: T[String] = Task { finalMainClass() }

  /**
   * The type of native package / installer to be created.
   *
   * Valid values are:
   *  "app-image" - any OS
   *  "dmg", "pkg" - macOS (native package, installer)
   *  "exe", "msi" - Windows (native package, installer)
   *  "rpm", "deb" - Linux
   *
   * If unspecified, defaults to "app-image" which will build a package native to the host platform.
   */
  def jpackageType: T[String] = Task { "app-image" }

  /**
   * The classpath used for the `jpackage` tool. The first entry needs to be the main jar.
   * In difference to [[runClasspath]], it contains the built jars of all dependent modules.
   */
  def jpackageRunClasspath: T[Seq[PathRef]] = Task {
    val recLocalClasspath = (localClasspath() ++ transitiveLocalClasspath()).map(_.path)

    val runCp = runClasspath().filterNot(pr => recLocalClasspath.contains(pr.path))

    val mainJar = jar()
    val recJars = transitiveJars()

    mainJar +: (recJars ++ runCp)
  }

  /** Builds a native package of the main application. */
  def jpackageAppImage: T[PathRef] = Task {
    // materialize all jars into a "lib" dir
    val libs = Task.dest / "lib"
    val cp = jpackageRunClasspath().map(_.path)
    val jars = cp.filter(os.exists).zipWithIndex.map { case (p, idx) =>
      val dest = libs / s"${idx + 1}-${p.last}"
      os.copy(p, dest, createFolders = true)
      dest
    }

    val appName = jpackageName()
    val appType = jpackageType()
    val mainClass = jpackageMainClass()
    val mainJarName = jars.head.last

    val args: Seq[String] = Seq(
      Jvm.jdkTool("jpackage", this.jvmWorker().javaHome().map(_.path)),
      "--type",
      appType,
      "--name",
      appName,
      "--input",
      libs.toString(),
      "--main-jar",
      mainJarName,
      "--main-class",
      mainClass
    )

    // run jpackage tool
    val outDest = Task.dest / "image"
    os.makeDir.all(outDest)
    os.proc(args).call(cwd = outDest)
    PathRef(outDest)
  }
}
