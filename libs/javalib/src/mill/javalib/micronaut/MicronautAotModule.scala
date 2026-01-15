package mill.javalib.micronaut

import mill.{T, Task}
import mill.api.PathRef
import mill.util.Jvm
import mill.javalib.{Dep, DepSyntax, JavaModule, NativeImageModule}

trait MicronautAotModule extends JavaModule, NativeImageModule {

  def resolvedMicronautAotCli: T[Seq[PathRef]] = defaultResolver().classpath(
    Seq(
      mvn"io.micronaut.aot:micronaut-aot-cli",
      mvn"io.micronaut.aot:micronaut-aot-api",
      mvn"io.micronaut.aot:micronaut-aot-core",
      mvn"io.micronaut.aot:micronaut-aot-std-optimizers"
    ),
    boms = allBomDeps()
  )

  /**
   * More information on configuring Micronaut AOT can be found
   * in [[https://micronaut-projects.github.io/micronaut-aot/latest/guide/configurationreference.html]]
   */
  def aotConfigFile: T[PathRef] = Task {
    val file = Task.dest / "micronaut-aot.properties"
    os.write(
      file,
      """
        |logback.xml.to.java.enabled=true
        |netty.properties.enabled=true
        |deduce.environment.enabled=true
        |serviceloading.native.enabled=false
        |precompute.environment.properties.enabled=true
        |sealed.property.source.enabled=true
        |cached.environment.enabled=true
        |graalvm.config.enabled=true
      """.stripMargin
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
      "hello.world",
      "--runtime",
      "native",
      "--config",
      aotConfigFile().path.toString,
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

  override def nativeImageClasspath: Task.Simple[Seq[PathRef]] = Task {
    val aotClasses = Seq(PathRef(micronautProcessAOT().path / "classes"))
    super.nativeImageClasspath() ++ aotClasses
  }

  override def nativeImageOptions: Task.Simple[Seq[String]] = Task {
    val configurationsPath = micronautProcessAOT().path / "classes/META-INF"
    super.nativeImageOptions() ++ Seq(
      "--no-fallback",
      "--configurations-path",
      configurationsPath.toString
    )
  }

}
