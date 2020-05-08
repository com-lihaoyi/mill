package mill.scalalib.publish

import utest._
import mill._

import scala.xml.{Node, NodeSeq, XML}

object IvyTests extends TestSuite {

  def tests: Tests = Tests {

    val dummyFile : PathRef =  PathRef(os.temp.dir() / "dummy.txt")

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

    val extras = Seq(
      PublishInfo(file = dummyFile, classifier = Some("dist"), ivyConfig = "compile"),
      PublishInfo(file = dummyFile, ivyType = "dist", ext = "zip", ivyConfig = "runtime", classifier = None)
    )

    'fullIvy - {
      val fullIvy = XML.loadString(Ivy(artifact, deps, extras))

      'topLevel - {
        val info = singleNode(fullIvy \ "info")
        assert(
          mandatoryAttr(info, "organisation") == artifact.group,
          mandatoryAttr(info, "module") == artifact.id,
          mandatoryAttr(info, "revision") == artifact.version
        )
      }

      'publications - {
        val publications : List[IvyInfo] = (fullIvy \ "publications" \ "artifact").iterator.map(IvyInfo.apply).toList
        assert(publications.size == 4 + extras.size)

        val expected : List[IvyInfo] = List(
          IvyInfo(artifact.id, "pom", "pom", "pom", None),
          IvyInfo(artifact.id, "jar", "jar", "compile", None),
          IvyInfo(artifact.id, "src", "jar", "compile", Some("sources")),
          IvyInfo(artifact.id, "doc", "jar", "compile", Some("javadoc")),
        ) ++ extras.map(e => IvyInfo(
          artifact.id, e.ivyType, e.ext, e.ivyConfig, e.classifier
        ))

        expected.foreach(exp => assert(publications.contains(exp)))
      }

      'dependencies - {
        val dependencies = fullIvy \ "dependencies" \ "dependency"
        val ivyDeps = deps.indexed

        assert(dependencies.size == ivyDeps.size)

        dependencies.zipWithIndex.foreach { case (dep, index) =>
          assert(
            mandatoryAttr(dep, "org") == ivyDeps(index).artifact.group,
            mandatoryAttr(dep, "name") == ivyDeps(index).artifact.id,
            mandatoryAttr(dep, "rev") == ivyDeps(index).artifact.version,
            (dep \ "exclude").zipWithIndex forall { case (exclude, j) =>
              mandatoryAttr(exclude, "org") == ivyDeps(index).exclusions(j)._1 &&
                mandatoryAttr(exclude, "name") == ivyDeps(index).exclusions(j)._2
            }
          )
        }
      }
    }
  }

  private def singleNode(seq: NodeSeq): Node =
    seq.headOption.getOrElse(throw new RuntimeException("empty seq"))

  private def mandatoryAttr(node: Node, attr: String): String =
    optionalAttr(node, attr).getOrElse(throw new RuntimeException(s"empty attr $attr"))

  private def optionalAttr(node : Node, attr: String) : Option[String] = {
    node.attributes.asAttrMap.get(attr)
  }

  private case class IvyInfo(
    name : String,
    ivyType : String,
    ext: String,
    ivyConf: String,
    classifier: Option[String]
  )

  private object IvyInfo {
    def apply(node : Node) : IvyInfo = {
      IvyInfo(
        name = mandatoryAttr(node, "name"),
        ivyType = mandatoryAttr(node, "type"),
        ext = mandatoryAttr(node, "ext"),
        ivyConf = mandatoryAttr(node, "conf"),
        classifier = optionalAttr(node, "e:classifier")
      )
    }
  }
}
