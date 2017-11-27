package mill.scalaplugin.publish

import scala.xml.{Elem, NodeSeq}


trait PomFile {

  val head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

  //TODO - not only jar packaging support?
  //TODO - description
  def generatePom(
    artifact: Artifact,
    dependencies: Seq[Dependency],
    pomSettings: PomSettings
  ): String = {
    val xml =
      <project
        xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"
        xmlns:xsi ="http://www.w3.org/2001/XMLSchema-instance"
        xmlns ="http://maven.apache.org/POM/4.0.0">

        <modelVersion>4.0.0</modelVersion>
        <artifactId>{artifact.id}</artifactId>
        <packaging>jar</packaging>
        <description></description>

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

    val pp = new scala.xml.PrettyPrinter(120, 4)
    val data = pp.format(xml)
    head + data
  }

  private def renderLicense(l: License): Elem = {
    import l._
    <license>
      <name>{name}</name>
      <url>{url}</url>
      <distribution>{distribution}</distribution>
    </license>
  }

  private def renderDeveloper(d: Developer): Elem = {
    import d._
    <developer>
      <id>{id}</id>
      <name>{name}</name>
      <organization>{organization}</organization>
      <organizationUrl>{organizationUrl}</organizationUrl>
    </developer>
  }

  private def renderDependency(d: Dependency): Elem = {
    import d._
    import artifact._

    val scope = d.scope match {
      case Scope.Compile => NodeSeq.Empty
      case Scope.Provided => <scope>provided</scope>
      case Scope.Test => <scope>test</scope>
      case Scope.Runtime => <scope>runtime</scope>
    }
    <dependency>
      <groupId>{group}</groupId>
      <artifactId>{id}</artifactId>
      <version>{version}</version>
      {scope}
    </dependency>
  }

}

object PomFile extends PomFile

