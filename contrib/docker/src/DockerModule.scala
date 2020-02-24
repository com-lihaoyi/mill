package mill
package contrib.docker

import mill.scalalib.JavaModule
import os.Shellable.IterableShellable

import scala.collection.immutable._

trait DockerModule { outer: JavaModule =>

  trait DockerConfig extends mill.Module {
    /**
      * Tags that should be applied to the built image
      * In the standard registry/repository:tag format
      */
    def tags: T[Seq[String]] = T(List(outer.artifactName()))
    def labels: T[Map[String, String]] = Map.empty[String, String]
    def baseImage: T[String] = "gcr.io/distroless/java:latest"
    def pullBaseImage: T[Boolean] = T(baseImage().endsWith(":latest"))
    private def baseImageCacheBuster: T[(Boolean, Double)] = T.input {
      val pull = pullBaseImage()
      if(pull) (pull, Math.random()) else (pull, 0d)
    }

    def dockerfile: T[String] = T {
      val jarName = assembly().path.last
      val labelRhs = labels()
        .map { case (k, v) =>
          val lineBrokenValue = v
            .replace("\r\n", "\\\r\n")
            .replace("\n", "\\\n")
            .replace("\r", "\\\r")
          s""""$k"="$lineBrokenValue""""
        }
        .mkString(" ")

      val labelLine = if(labels().isEmpty) "" else s"LABEL $labelRhs"

      s"""
         |FROM ${baseImage()}
         |$labelLine
         |COPY $jarName /$jarName
         |ENTRYPOINT ["java", "-jar", "/$jarName"]
      """.stripMargin
    }

    final def build = T {
      val dest = T.dest

      val asmPath = outer.assembly().path
      os.copy(asmPath, dest / asmPath.last)

      os.write(dest / "Dockerfile", dockerfile())

      val log = T.log

      val tagArgs = tags().flatMap(t => List("-t", t))

      val (pull, _) = baseImageCacheBuster()
      val pullLatestBase = IterableShellable(if(pull) Some("--pull") else None)

      val result = os
        .proc("docker", "build", tagArgs, pullLatestBase, dest)
        .call(stdout = os.Inherit, stderr = os.Inherit)

      log.info(s"Docker build completed ${if(result.exitCode == 0) "successfully" else "unsuccessfully"} with ${result.exitCode}")
      tags()
    }

    final def push() = T.command {
      val tags = build()
      tags.foreach(t => os.proc("docker", "push", t).call(stdout = os.Inherit, stderr = os.Inherit))
    }
  }
}
