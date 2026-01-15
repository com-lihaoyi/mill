package mill.javalib.micronaut

import mill.api.PathRef
import mill.javalib.NativeImageModule
import mill.{T, Task}

trait MicronautNativeAotModule extends MicronautAotModule, NativeImageModule {

  override def aotRuntime: T[String] = Task {
    "native"
  }

  override def micronautAotConfigProperties: T[Map[String, String]] = Task {
    val nativeProperties = Map(
      "serviceloading.native.enabled" -> "true",
      "graalvm.config.enabled" -> "true"
    )
    // Remove the JIT specific property
    val base = super.micronautAotConfigProperties() - "serviceloading.jit.enabled"
    base ++ nativeProperties
  }

  override def nativeImageClasspath: T[Seq[PathRef]] = Task {
    super.nativeImageClasspath() ++ micronautAotClasspath()
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
