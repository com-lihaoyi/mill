package mill.javalib.micronaut

import mill.{T, Task}
import mill.api.{PathRef, experimental}
import mill.util.Jvm
import mill.javalib.{Dep, DepSyntax, JavaModule}

@experimental
trait MicronautAotModule extends JavaModule {

  def micronautPackage: String

  protected def aotRuntime: T[String] = Task {
    "jit"
  }

  /**
   * The Micronaut AOT CLI dependencies resolved.
   *
   * The version is expected to be managed
   * via the micronaut-platform or micronaut-aot-bom
   * in the [[bomMvnDeps]].
   */
  def resolvedMicronautAotCli: T[Seq[PathRef]] = Task {
    try {
      defaultResolver().classpath(
        Seq(
          mvn"io.micronaut.aot:micronaut-aot-cli"
        ),
        boms = allBomDeps()
      )
    } catch {
      case e: RuntimeException =>
        Task.fail(
          s"Failed to resolve Micronaut AOT CLI dependencies. Make sure to include the micronaut-platform or micronaut-aot-bom in your bomMvnDeps.\n${e.getMessage}"
        )
    }
  }

  /**
   * Configuration properties to be used for Micronaut AOT processing.
   * More information on configuring Micronaut AOT can be found
   * in [[https://micronaut-projects.github.io/micronaut-aot/latest/guide/configurationreference.html]]
   */
  def micronautAotConfigProperties: T[Map[String, String]] = Task {
    Map(
      "logback.xml.to.java.enabled" -> "true",
      "netty.properties.enabled" -> "true",
      "deduce.environment.enabled" -> "true",
      "serviceloading.jit.enabled" -> "false",
      "precompute.environment.properties.enabled" -> "true",
      "sealed.property.source.enabled" -> "true",
      "cached.environment.enabled" -> "true"
    )
  }

  /**
   * Generates the Micronaut AOT configuration file
   * with the properties from [[micronautAotConfigProperties]]
   */
  def micronautAotConfigFile: T[PathRef] = Task {
    val file = Task.dest / "micronaut-aot.properties"
    os.write(
      file,
      micronautAotConfigProperties().map { case (k, v) => s"$k=$v" }.mkString("\n")
    )
    PathRef(file)
  }

  /**
   * The settings can be found in
   * [[https://github.com/micronaut-projects/micronaut-aot/blob/3.0.x/aot-cli/src/main/java/io/micronaut/aot/cli/Main.java]]
   */
  def micronautProcessAOT: T[PathRef] = Task {
    val dest = Task.dest

    val args = Seq(
      "--classpath",
      (runClasspath() ++ resolvedMicronautAotCli()).map(_.path).mkString(":"),
      "--package",
      micronautPackage,
      "--runtime",
      aotRuntime(),
      "--config",
      micronautAotConfigFile().path.toString,
      "--output",
      dest.toString
    )

    Jvm.callProcess(
      mainClass = "io.micronaut.aot.cli.Main",
      mainArgs = args,
      classPath = resolvedMicronautAotCli().map(_.path)
    )

    PathRef(dest)
  }

  /**
   * The generated classes from Micronaut AOT processing to be
   * included in the classpath for running, or building native images.
   */
  def micronautAotClasspath: T[Seq[PathRef]] = Task {
    Seq(PathRef(micronautProcessAOT().path / "classes"))
  }

}
