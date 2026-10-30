package mill.javalib.micronaut

import mill.api.{PathRef, experimental}
import mill.javalib.NativeImageModule
import mill.{T, Task}
import mill.api.opt.*

/**
 * An extension of [[MicronautAotModule]] that provides
 * configuration of Micronaut AOT processing for native GraalVM images.
 */
@experimental
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

  override def nativeImageOptions: Task.Simple[Opts] = Task {
    val configurationsPath = micronautProcessAOT().path / "classes/META-INF"
    super.nativeImageOptions() ++ Opts(
      "--no-fallback",
      OptGroup(
        "--configurations-path",
        configurationsPath
      )
    )
  }

}
