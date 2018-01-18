package mill.scalalib.publish

import mill.util.Loose.Agg

import scala.xml.{Elem, NodeSeq, PrettyPrinter}

object Pom {

  val head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

  //TODO - not only jar packaging support?
  def apply(artifact: Artifact,
            dependencies: Agg[Dependency],
            name: String,
            pomSettings: PomSettings): String = {
    val xml =
      <project
        xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"
        xmlns:xsi ="http://www.w3.org/2001/XMLSchema-instance"
        xmlns ="http://maven.apache.org/POM/4.0.0">

        <modelVersion>4.0.0</modelVersion>
        <name>{name}</name>
        <groupId>{artifact.group}</groupId>
        <artifactId>{artifact.id}</artifactId>
        <packaging>jar</packaging>
        <description>{pomSettings.description}</description>

        <version>{artifact.version}</version>
        <url>{pomSettings.url}</url>
        <licenses>
          {pomSettings.licenses.map(renderLicense)}
        </licenses>
        <scm>
          <url>{pomSettings.scm.url}</url>
          <connection>{pomSettings.scm.connection}</connection>
        </scm>
        <developers>
          {pomSettings.developers.map(renderDeveloper)}
        </developers>
        <dependencies>
          {dependencies.map(renderDependency)}
        </dependencies>
      </project>

    val pp = new PrettyPrinter(120, 4)
    head + pp.format(xml)
  }

  private def renderLicense(l: License): Elem = {
    <license>
      <name>{l.name}</name>
      <url>{l.url}</url>
      <distribution>{l.distribution}</distribution>
    </license>
  }

  private def renderDeveloper(d: Developer): Elem = {
    <developer>
      <id>{d.id}</id>
      <name>{d.name}</name>
      {
        d.organization.map { org =>
          <organization>{org}</organization>
        }.getOrElse(NodeSeq.Empty)
      }
      {
        d.organizationUrl.map { orgUrl =>
          <organizationUrl>{orgUrl}</organizationUrl>
        }.getOrElse(NodeSeq.Empty)
      }
    </developer>
  }

  private def renderDependency(d: Dependency): Elem = {
    val scope = d.scope match {
      case Scope.Compile  => NodeSeq.Empty
      case Scope.Provided => <scope>provided</scope>
      case Scope.Test     => <scope>test</scope>
      case Scope.Runtime  => <scope>runtime</scope>
    }
    <dependency>
      <groupId>{d.artifact.group}</groupId>
      <artifactId>{d.artifact.id}</artifactId>
      <version>{d.artifact.version}</version>
      {scope}
    </dependency>
  }

}
