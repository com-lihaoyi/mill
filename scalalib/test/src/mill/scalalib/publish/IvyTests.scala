package mill.scalalib.publish

import utest._
import mill._

import scala.xml.{Node, NodeSeq, XML}

object IvyTests extends TestSuite {

  def tests: Tests = Tests {
    val artifactId = "mill-scalalib_2.12"
    val artifact =
      Artifact("com.lihaoyi", "mill-scalalib_2.12", "0.0.1")
    val deps = Agg(
      Dependency(Artifact("com.lihaoyi", "mill-main_2.12", "0.1.4"),
        Scope.Compile),
      Dependency(Artifact("org.scala-sbt", "test-interface", "1.0"),
        Scope.Compile),
      Dependency(Artifact("com.lihaoyi", "pprint_2.12", "0.5.3"),
        Scope.Compile, exclusions = List("com.lihaoyi" -> "fansi_2.12", "*" -> "sourcecode_2.12"))
    )

    'fullIvy - {
      val fullIvy = XML.loadString(Ivy(artifact, deps))

      'topLevel - {
        val info = singleNode(fullIvy \ "info")
        assert(
          singleAttr(info, "organisation") == artifact.group,
          singleAttr(info, "module") == artifact.id,
          singleAttr(info, "revision") == artifact.version
        )
      }

      'dependencies - {
        val dependencies = fullIvy \ "dependencies" \ "dependency"
        val ivyDeps = deps.indexed

        assert(dependencies.size == ivyDeps.size)

        dependencies.zipWithIndex.foreach { case (dep, index) =>
          assert(
            singleAttr(dep, "org") == ivyDeps(index).artifact.group,
            singleAttr(dep, "name") == ivyDeps(index).artifact.id,
            singleAttr(dep, "rev") == ivyDeps(index).artifact.version,
            (dep \ "exclude").zipWithIndex forall { case (exclude, j) =>
              singleAttr(exclude, "org") == ivyDeps(index).exclusions(j)._1 &&
                singleAttr(exclude, "name") == ivyDeps(index).exclusions(j)._2
            }
          )
        }
      }
    }
  }

  def singleNode(seq: NodeSeq): Node =
    seq.headOption.getOrElse(throw new RuntimeException("empty seq"))
  def singleAttr(node: Node, attr: String): String =
    node.attribute(attr).flatMap(_.headOption.map(_.text)).getOrElse(throw new RuntimeException(s"empty attr $attr"))
}
