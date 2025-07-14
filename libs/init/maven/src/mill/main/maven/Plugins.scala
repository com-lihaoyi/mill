package mill.main.maven

import mill.api.daemon.internal.internal
import org.apache.maven.model.{Model, Plugin}
import org.codehaus.plexus.util.xml.Xpp3Dom

import scala.jdk.CollectionConverters.*

/** Utilities for handling Maven plugins. */
@internal
object Plugins {

  def find(model: Model, groupId: String, artifactId: String): Option[Plugin] =
    model.getBuild.getPlugins.asScala
      .find(p => p.getGroupId == groupId && p.getArtifactId == artifactId)

  def dom(plugin: Plugin): Option[Xpp3Dom] =
    plugin.getConfiguration match {
      case xpp3: Xpp3Dom => Some(xpp3)
      case _ => None
    }

  /**
   * @see [[https://maven.apache.org/plugins/maven-compiler-plugin/index.html]]
   */
  object MavenCompilerPlugin {

    def find(model: Model): Option[Plugin] =
      Plugins.find(model, "org.apache.maven.plugins", "maven-compiler-plugin")

    def javacOptions(model: Model): Seq[String] = {
      val options = Seq.newBuilder[String]
      find(model).flatMap(dom).foreach { dom =>
        // javac throws exception if release is specified with source/target, and
        // plugin configuration returns default values for source/target when not specified
        val release = dom.child("release")
        if (null == release) {
          dom.child("source").foreachValue(options += "-source" += _)
          dom.child("target").foreachValue(options += "-target" += _)
        } else {
          options += "--release" += release.getValue
        }
        dom.child("encoding").foreachValue(options += "-encoding" += _)
        dom.child("compilerArgs").foreachChildValue(options += _)
      }

      options.result()
    }
  }

  private implicit class NullableDomOps(val self: Xpp3Dom) extends AnyVal {

    def child(name: String): Xpp3Dom =
      if (null == self) self else self.getChild(name)

    def foreachValue(f: String => Unit): Unit =
      if (null != self) f(self.getValue)

    def foreachChildValue(f: String => Unit): Unit =
      if (null != self) self.getChildren.iterator.foreach(dom => f(dom.getValue))
  }
}
