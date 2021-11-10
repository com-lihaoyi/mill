package mill
package contrib.docker

import mill.scalalib.JavaModule
import mill.api.Logger

import com.google.cloud.tools.jib.api.Containerizer
import com.google.cloud.tools.jib.api.TarImage
import com.google.cloud.tools.jib.api.Jib
import com.google.cloud.tools.jib.api.JibEvent
import com.google.cloud.tools.jib.api.LogEvent
import com.google.cloud.tools.jib.api.RegistryImage
import com.google.cloud.tools.jib.api.DockerDaemonImage
import com.google.cloud.tools.jib.api.ImageReference
import com.google.cloud.tools.jib.api.buildplan.Port
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory

import java.nio.file.Paths
import java.util.Arrays

import scala.collection.immutable._
import scala.collection.JavaConverters._

trait DockerModule { outer: JavaModule =>

  trait DockerConfig extends mill.Module {

    /**
     * Tags that should be applied to the built image
     * In the standard registry/repository:tag format
     */
    def tags: T[Seq[String]] = T(List(outer.artifactName()))
    def labels: T[Map[String, String]] = Map.empty[String, String]
    def baseImage: T[String] = "gcr.io/distroless/java11-debian11:latest"
    def pullBaseImage: T[Boolean] = T(baseImage().endsWith(":latest"))

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
     * Any applicable string to the USER instruction.
     *
     * An empty string will be ignored and will result in USER not being
     * specified.  See also the Docker docs on
     * [[https://docs.docker.com/engine/reference/builder/#user USER]]
     * for more information.
     */
    def user: T[String] = ""

    private def getJibEventHandler(log: Logger): java.util.function.Consumer[JibEvent] = {
      (event: JibEvent) => {
        event match {
          case event: LogEvent => event.getLevel match {
            case LogEvent.Level.ERROR => log.error(event.getMessage)
            case LogEvent.Level.LIFECYCLE => // ignore
            case _ => log.info(s"[${event.getLevel.toString.toLowerCase}] ${event.getMessage}")
          }
          case _ =>
        }
      }
    }

    final def build = T {
      val out = T.dest / "image.tar"

      val asmPath = outer.assembly().path
      val jarName = asmPath.last

      val log = T.log
      val eventHandler = getJibEventHandler(log)

      val targetTar = Containerizer.to(
        TarImage.at(Paths.get(out.toString)).named(tags().head)
      ).setAlwaysCacheBaseImage(pullBaseImage()).addEventHandler(eventHandler)

      val targetTarWithTags = tags().tail.foldLeft(targetTar)(
        (target, tag) => target.withAdditionalTag(tag)
      )

      val allExposedPorts =
        exposedPorts().map((port) => Port.tcp(port)).toSet ++
        exposedUdpPorts().map((port) => Port.udp(port)).toSet

      val builder = Jib
        .from(baseImage())
        .addLayer(
          Arrays.asList(Paths.get(asmPath.toString)),
          "/app/"
        )
        .setEntrypoint(Arrays.asList("/usr/bin/java", "-jar", "/app/" + jarName))
        .setLabels(labels().asJava)
        .setVolumes(volumes().map((volume) => AbsoluteUnixPath.get(volume)).toSet.asJava)
        .setEnvironment(envVars().asJava)
        .setExposedPorts(allExposedPorts.asJava)

      if(!user().isEmpty){
        builder.setUser(user())
      }

      builder.containerize(targetTarWithTags)

      PathRef(out)
    }

    final def load() = T.command {
      val image = build()
      val source = TarImage.at(Paths.get(image.path.toString))

      val log = T.log
      val eventHandler = getJibEventHandler(log)

      tags().foreach(t => {
        val targetRef = ImageReference.parse(t)
        val target = Containerizer.to(DockerDaemonImage.named(targetRef)).addEventHandler(eventHandler)
        Jib.from(source).containerize(target)
      })
    }

    final def push() = T.command {
      val image = build()
      val source = TarImage.at(Paths.get(image.path.toString))

      val log = T.log
      val eventHandler = getJibEventHandler(log)

      tags().foreach(tag => {
        val targetRef = ImageReference.parse(tag)
        val target = Containerizer.to(RegistryImage.named(targetRef).addCredentialRetriever(
          CredentialRetrieverFactory.forImage(targetRef, (event) => {
            eventHandler.accept(event)
          }).dockerConfig()
        )).addEventHandler(eventHandler)
        Jib.from(source).containerize(target)
      })
    }
  }
}
