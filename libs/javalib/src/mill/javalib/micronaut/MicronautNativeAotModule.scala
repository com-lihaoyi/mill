package mill.javalib.micronaut

import mill.api.PathRef
import mill.{T, Task}

trait MicronautNativeAotModule extends MicronautAotModule {

  override def aotRuntime: T[String] = Task {
    "native"
  }

  override def micronautAotConfigProperties: T[Map[String, String]] = Task {
    super.micronautAotConfigProperties() - "serviceloading.jit.enabled"
      ++ Map(
        "serviceloading.native.enabled" -> "true",
        "graalvm.config.enabled" -> "true"
      )
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
