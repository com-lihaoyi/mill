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

    /**
     * JVM runtime options. Each item of the Seq should consist of an option and its desired value, like
     * {{{
     * def jvmOptions = Seq("-Xmx1024M", "-agentlib:jdwp=transport=dt_socket,server=y,address=8000", …)
     * }}}
     * For a full list of options consult the official documentation at
     * [[https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html#overview-of-java-options]]
     */
    def jvmOptions: T[Seq[String]] = Seq.empty[String]

    /**
     * TCP Ports the container will listen to at runtime.
     *
     * See also the Docker docs on
     * [[https://docs.docker.com/engine/reference/builder/#expose ports]] for
     * more information.
     */
    def exposedPorts: T[Seq[Int]] = Seq.empty[Int]

    /**
     * UDP Ports the container will listen to at runtime.
     *
     * See also the Docker docs on
     * [[https://docs.docker.com/engine/reference/builder/#expose ports]] for
     * more information.
     */
    def exposedUdpPorts: T[Seq[Int]] = Seq.empty[Int]

    /**
     * The names of mount points.
     *
     * See also the Docker docs on
     * [[https://docs.docker.com/engine/reference/builder/#volume volumes]]
     * for more information.
     */
    def volumes: T[Seq[String]] = Seq.empty[String]

    /**
     * Environment variables to be set in the container.
     *
     * See also the Docker docs on
     * [[https://docs.docker.com/engine/reference/builder/#env ENV]]
     * for more information.
     */
    def envVars: T[Map[String, String]] = Map.empty[String, String]

    /**
     * Commands to add as RUN instructions.
     *
     * See also the Docker docs on
     * [[https://docs.docker.com/engine/reference/builder/#run RUN]]
     * for more information.
     */
    def run: T[Seq[String]] = Seq.empty[String]

    /**
     * Any applicable string to the USER instruction.
     *
     * An empty string will be ignored and will result in USER not being
     * specified.  See also the Docker docs on
     * [[https://docs.docker.com/engine/reference/builder/#user USER]]
     * for more information.
     */
    def user: T[String] = ""

    /**
     * Optional platform parameter, if set uses buildkit to build for specified platform.
     *
     * See also the Docker docs on
     * [[https://docs.docker.com/reference/cli/docker/buildx/build/#platform]]
     * for more information.
     */
    def platform: T[String] = ""

    /**
     * The name of the executable to use, the default is "docker".
     */
    def executable: T[String] = "docker"

    def dockerfile: T[String] = Task {
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

      val lines = List(
        if (labels().isEmpty) "" else s"LABEL $labelRhs",
        if (exposedPorts().isEmpty) ""
        else exposedPorts().map(port => s"$port/tcp")
          .mkString("EXPOSE ", " ", ""),
        if (exposedUdpPorts().isEmpty) ""
        else exposedUdpPorts().map(port => s"$port/udp")
          .mkString("EXPOSE ", " ", ""),
        envVars().map { case (env, value) =>
          s"ENV $env=$value"
        }.mkString("\n"),
        if (volumes().isEmpty) ""
        else volumes().map(v => s"\"$v\"").mkString("VOLUME [", ", ", "]"),
        run().map(c => s"RUN $c").mkString("\n"),
        if (user().isEmpty) "" else s"USER ${user()}"
      ).filter(_.nonEmpty).mkString(sys.props.getOrElse("line.separator", ???))

      val quotedEntryPointArgs = (Seq("java") ++ jvmOptions() ++ Seq("-jar", s"/$jarName"))
        .map(arg => s"\"$arg\"").mkString(", ")

      s"""
         |FROM ${baseImage()}
         |$lines
         |COPY $jarName /$jarName
         |ENTRYPOINT [$quotedEntryPointArgs]""".stripMargin
    }

    private def pullAndHash = Task.Input {
      def imageHash() =
        os.proc(executable(), "images", "--no-trunc", "--quiet", baseImage())
          .call(stderr = os.Inherit).out.text().trim

      if (pullBaseImage() || imageHash().isEmpty)
        os.proc(executable(), "image", "pull", baseImage())
          .call(stdout = os.Inherit, stderr = os.Inherit)

      (pullBaseImage(), imageHash())
    }

    final def build = Task {
      val dest = T.dest

      val asmPath = outer.assembly().path
      os.copy(asmPath, dest / asmPath.last)

      os.write(dest / "Dockerfile", dockerfile())

      val log = T.log

      val tagArgs = tags().flatMap(t => List("-t", t))

      val (pull, _) = pullAndHash()
      val pullLatestBase = IterableShellable(if (pull) Some("--pull") else None)

      val result = if (platform().isEmpty || executable() != "docker") {
        if (platform().nonEmpty)
          log.info("Platform parameter is ignored when using non-docker executable")
        os.proc(executable(), "build", tagArgs, pullLatestBase, dest)
          .call(stdout = os.Inherit, stderr = os.Inherit)
      } else {
        os.proc(
          executable(),
          "buildx",
          "build",
          tagArgs,
          pullLatestBase,
          "--platform",
          platform(),
          dest
        )
          .call(stdout = os.Inherit, stderr = os.Inherit)
      }
      log.info(s"Docker build completed ${if (result.exitCode == 0) "successfully"
        else "unsuccessfully"} with ${result.exitCode}")
      tags()
    }

    final def push() = Task.Command {
      val tags = build()
      tags.foreach(t =>
        os.proc(executable(), "push", t).call(stdout = os.Inherit, stderr = os.Inherit)
      )
    }
  }
}
