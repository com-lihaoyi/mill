package mill.init.maven

import org.apache.maven.model.{Model, Plugin}
import org.codehaus.plexus.util.xml.Xpp3Dom

import scala.jdk.CollectionConverters.*

/** Utilities for reading Maven plugin configurations */
private[maven] object plugin {

  private def find(model: Model, groupId: String, artifactId: String): Option[Plugin] =
    model.getBuild.getPlugins.asScala
      .find(p => p.getGroupId == groupId && p.getArtifactId == artifactId)

  private def dom(plugin: Plugin): Option[Xpp3Dom] =
    plugin.getConfiguration match {
      case xpp3: Xpp3Dom => Some(xpp3)
      case _ => None
    }

  /**
   * @see [[https://maven.apache.org/plugins/maven-compiler-plugin/index.html]]
   */
  object `maven-compiler-plugin` {

    private def find(model: Model): Option[Plugin] =
      plugin.find(model, "org.apache.maven.plugins", "maven-compiler-plugin")

    def javacOptions(model: Model): Seq[String] =
      find(model).flatMap(dom).fold(Seq.empty[String]) { dom =>
        val b = Seq.newBuilder[String]

        // javac throws exception if release is specified with source/target, and
        // plugin configuration returns default values for source/target when not specified
        val release = dom.getChild("release")
        if (release == null) {
          Option(dom.getChild("source")).foreach(b += "-source" += _.getValue)
          Option(dom.getChild("target")).foreach(b += "-target" += _.getValue)
        } else {
          b += "--release" += release.getValue
        }
        Option(dom.getChild("encoding")).foreach(b += "-encoding" += _.getValue)
        Option(dom.getChild("compilerArgs")).flatMap(dom => Option(dom.getChildren))
          .foreach(args => b ++= args.iterator.map(_.getValue))

        b.result()
      }
  }
}
