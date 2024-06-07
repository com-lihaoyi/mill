package jpackage

import mill._
import mill.scalalib.JavaModule

trait Jpackage extends JavaModule {

  /** The application name */
  def jpackageName: T[String] = T { artifactName() }

  def jpackageMainClass: T[String] = T { finalMainClass() }

  def transitiveJars: T[Seq[PathRef]] = T {
    T.traverse(transitiveModuleCompileModuleDeps)(_.jar)()
  }

  /**
   * The classpath used for the jpackage tool. The first entry is the main jar.
   * In difference to [[runClasspath]], it contains the built jars of all dependent modules.
   */
  def jpackageRunClasspath: T[Seq[PathRef]] = T {
    val recLocalClasspath = (localClasspath() ++ transitiveLocalClasspath()).map(_.path)

    val runCp = runClasspath().filterNot(pr => recLocalClasspath.contains(pr.path))

    val mainJar = jar()
    val recJars = transitiveJars()

    mainJar +: (recJars ++ runCp)
  }

  /** Builds a native binary from the main application. */
  def jpackage: T[PathRef] = T {
    // materialize all jars into a "lib" dir
    val libs = T.dest / "lib"
    val cp = jpackageRunClasspath().map(_.path)
    var counter = 1
    val jars = cp.filter(os.exists).map { p =>
      val dest = libs / s"${counter}-${p.last}"
      os.copy(p, dest, createFolders = true)
      counter += 1
      dest
    }

    val appName = jpackageName()
    val mainClass = jpackageMainClass()
    val mainJarName = jars.head.last

    // TODO: runtime java options, e.g. env and stuff

    val args: Seq[String] = Seq(
      "jpackage",
      "--type",
      "app-image",
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
    val outDest = T.dest / "out"
    os.makeDir.all(outDest)
    os.proc(args).call(cwd = outDest)
    PathRef(outDest / appName / "bin" / appName)
  }

}
