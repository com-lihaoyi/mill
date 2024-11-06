package mill.main.maven

import org.apache.maven.model.{Model, Plugin, PluginExecution}
import org.codehaus.plexus.util.xml.Xpp3Dom

import scala.jdk.CollectionConverters.*

/** Utilities for handling Maven plugins. */
@mill.api.internal
object Plugins {

  def find(model: Model, groupId: String, artifactId: String): Option[Plugin] =
    model.getBuild.getPlugins.asScala
      .find(p => p.getGroupId == groupId && p.getArtifactId == artifactId)

  def dom(plugin: Plugin): Option[Xpp3Dom] =
    plugin.getConfiguration match {
      case xpp3: Xpp3Dom => Some(xpp3)
      case _ => None
    }

  def dom(execution: PluginExecution): Option[Xpp3Dom] =
    execution.getConfiguration match {
      case xpp3: Xpp3Dom => Some(xpp3)
      case _ => None
    }

  /**
   * @see [[https://maven.apache.org/plugins/maven-compiler-plugin/index.html]]
   */
  object `maven-compiler-plugin` {

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

  /**
   * @see [[https://www.mojohaus.org/build-helper-maven-plugin/index.html]]
   */
  object `build-helper-maven-plugin` {

    type Sources = Seq[String]
    type Resources = Seq[String]
    type CompileSources = (Sources, Resources)
    type TestSources = (Sources, Resources)

    def find(model: Model): Option[Plugin] =
      Plugins.find(model, "org.codehaus.mojo", "build-helper-maven-plugin")

    def added(model: Model): (CompileSources, TestSources) = {
      val sources = Seq.newBuilder[String]
      val resources = Seq.newBuilder[String]
      val testSources = Seq.newBuilder[String]
      val testResources = Seq.newBuilder[String]
      find(model).foreach { plugin =>
        val dir = os.Path(model.getProjectDirectory)
        def ref(path: String): String = {
          val rel = os.Path(path).relativeTo(dir)
          s"PathRef(millSourcePath / \"$rel\")"
        }

        plugin.getExecutions.iterator().asScala.foreach { execution =>
          dom(execution).foreach { dom =>
            execution.getGoals.iterator().asScala.foreach {
              case "add-source" =>
                dom.child("sources").children("source")
                  .foreachChildValue(path => sources += ref(path))
              case "add-resource" =>
                dom.child("resources").children("resource").child("directory")
                  .foreachChildValue(path => resources += ref(path))
              case "add-test-source" =>
                dom.child("sources").children("source")
                  .foreachChildValue(path => testSources += ref(path))
              case "add-test-resource" =>
                dom.child("resources").children("resource").child("directory")
                  .foreachChildValue(path => testResources += ref(path))
              case _ =>
            }
          }
        }
      }
      ((sources.result(), resources.result()), (testResources.result(), testResources.result()))
    }
  }

  private implicit class NullableDomOps(val self: Xpp3Dom) extends AnyVal {

    def child(name: String): Xpp3Dom =
      if (null == self) self else self.getChild(name)

    def children(name: String): Iterator[Xpp3Dom] =
      if (null == self) Iterator.empty else self.getChildren(name).iterator

    def foreachValue(f: String => Unit): Unit =
      if (null != self) f(self.getValue)

    def foreachChildValue(f: String => Unit): Unit =
      if (null != self) self.getChildren.iterator.foreach(dom => f(dom.getValue))
  }

  private implicit class IteratorDomOps(val self: Iterator[Xpp3Dom]) extends AnyVal {

    def child(name: String): Iterator[Xpp3Dom] =
      self.map(_.getChild(name))

    def foreachChildValue(f: String => Unit): Unit =
      self.foreach(dom => f(dom.getValue))
  }
}
