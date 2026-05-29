package mill.javalib.micronaut

import mill.api.{PathRef, experimental}
import mill.javalib.NativeImageModule
import mill.{T, Task}

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
      "graalvm.config.enabled" -> "true",
      // Logback AOT transformation currently causes native-image initialization issues on newer GraalVMs.
      "logback.xml.to.java.enabled" -> "false"
    )
    // Remove the JIT specific property
    val base = super.micronautAotConfigProperties() - "serviceloading.jit.enabled"
    base ++ nativeProperties
  }

  override def nativeImageClasspath: T[Seq[PathRef]] = Task {
    super.nativeImageClasspath() ++ micronautAotClasspath()
  }

  override def nativeImageOptions: Task.Simple[Seq[String]] = Task {
    val configurationsPath = micronautProcessAOT().path / "classes"
    // Pass a real, absolute on-disk path; in reproducible-build mode `os.Path.toString` is
    // relativized to an `out/mill-workspace/...` alias path that native-image cannot resolve.
    val configurationsPathAbs =
      try configurationsPath.wrapped.toRealPath().toString
      catch {
        case _: java.io.IOException =>
          mill.util.Jvm.realAbs(configurationsPath)
      }
    super.nativeImageOptions() ++ Seq(
      "--no-fallback",
      "--configurations-path",
      configurationsPathAbs
    )
  }

}
