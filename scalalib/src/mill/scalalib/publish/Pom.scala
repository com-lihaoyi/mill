package mill.scalalib.publish

import mill.api.Loose.Agg

import scala.xml.{Atom, Elem, NodeSeq, PrettyPrinter}

object Pom {

  val head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

  implicit class XmlOps(val e: Elem) extends AnyVal {
    // source: https://stackoverflow.com/a/5254068/449071
    def optional: NodeSeq = {
      require(e.child.length == 1)
      e.child.head match {
        case atom: Atom[Option[_]] => atom.data match {
            case None => NodeSeq.Empty
            case Some(x) => e.copy(child = x match {
                case n: NodeSeq => n
                case x => new Atom(x)
              })
          }
        case _ => e
      }
    }
  }

  @deprecated("Use overload with packagingType parameter instead", "Mill 0.11.8")
  def apply(
      artifact: Artifact,
      dependencies: Agg[Dependency],
      name: String,
      pomSettings: PomSettings,
      properties: Map[String, String]
  ): String = apply(
    artifact = artifact,
    dependencies = dependencies,
    name = name,
    pomSettings = pomSettings,
    properties = properties,
    packagingType = pomSettings.packaging,
    parentProject = None,
    bomDependencies = Agg.empty[Dependency]
  )

  @deprecated(
    "Use overload with parentProject and bomDependencies parameter instead",
    "Mill 0.12.1"
  )
  def apply(
      artifact: Artifact,
      dependencies: Agg[Dependency],
      name: String,
      pomSettings: PomSettings,
      properties: Map[String, String],
      packagingType: String
  ): String = apply(
    artifact = artifact,
    dependencies = dependencies,
    name = name,
    pomSettings = pomSettings,
    properties = properties,
    packagingType = packagingType,
    parentProject = None,
    bomDependencies = Agg.empty[Dependency]
  )

  def apply(
      artifact: Artifact,
      dependencies: Agg[Dependency],
      name: String,
      pomSettings: PomSettings,
      properties: Map[String, String],
      packagingType: String,
      parentProject: Option[Artifact],
      bomDependencies: Agg[Dependency]
  ): String = {
    val xml =
      <project
        xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"
        xmlns:xsi ="http://www.w3.org/2001/XMLSchema-instance"
        xmlns ="http://maven.apache.org/POM/4.0.0">

        <modelVersion>4.0.0</modelVersion>
        {parentProject.fold(NodeSeq.Empty)(renderParent)}
        <name>{name}</name>
        <groupId>{artifact.group}</groupId>
        <artifactId>{artifact.id}</artifactId>
        <packaging>{packagingType}</packaging>
        <description>{pomSettings.description}</description>

        <version>{artifact.version}</version>
        <url>{pomSettings.url}</url>
        <licenses>
          {pomSettings.licenses.map(renderLicense)}
        </licenses>
        <scm>
          {<connection>{pomSettings.versionControl.connection}</connection>.optional}
          {
        <developerConnection>{
          pomSettings.versionControl.developerConnection
        }</developerConnection>.optional
      }
          {<tag>{pomSettings.versionControl.tag}</tag>.optional}
          {<url>{pomSettings.versionControl.browsableRepository}</url>.optional}
        </scm>
        <developers>
          {pomSettings.developers.map(renderDeveloper)}
        </developers>
        <properties>
          {properties.map(renderProperty _).iterator}
        </properties>
        <dependencies>
          {
        dependencies.map(renderDependency(_)).iterator ++
          bomDependencies.map(renderDependency(_, isImport = true)).iterator
      }
        </dependencies>
      </project>

    val pp = new PrettyPrinter(120, 4)
    head + pp.format(xml)
  }

  private def renderParent(artifact: Artifact): Elem = {
    <parent>
      <groupId>{artifact.group}</groupId>
      <artifactId>{artifact.id}</artifactId>
      <version>{artifact.version}</version>
    </parent>
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
      <url>{d.url}</url>
      {<organization>{d.organization}</organization>.optional}
      {<organizationUrl>{d.organizationUrl}</organizationUrl>.optional}
    </developer>
  }

  private def renderProperty(property: (String, String)): Elem = {
    <prop>{property._2}</prop>.copy(label = property._1)
  }

  private def renderDependency(d: Dependency, isImport: Boolean = false): Elem = {
    val scope =
      if (isImport) <scope>import</scope>
      else
        d.scope match {
          case Scope.Compile => NodeSeq.Empty
          case Scope.Provided => <scope>provided</scope>
          case Scope.Test => <scope>test</scope>
          case Scope.Runtime => <scope>runtime</scope>
        }

    val `type` = if (isImport) <type>pom</type> else NodeSeq.Empty

    val optional = if (d.optional) <optional>true</optional> else NodeSeq.Empty

    val version =
      if (d.artifact.version == "_") NodeSeq.Empty
      else <version>{d.artifact.version}</version>

    if (d.exclusions.isEmpty)
      <dependency>
        <groupId>{d.artifact.group}</groupId>
        <artifactId>{d.artifact.id}</artifactId>
        {version}
        {scope}
        {`type`}
        {optional}
      </dependency>
    else
      <dependency>
        <groupId>{d.artifact.group}</groupId>
        <artifactId>{d.artifact.id}</artifactId>
        {version}
        <exclusions>
          {
        d.exclusions.map(ex => <exclusion>
              <groupId>{ex._1}</groupId>
              <artifactId>{ex._2}</artifactId>
            </exclusion>)
      }
        </exclusions>
        {scope}
        {`type`}
        {optional}
      </dependency>
  }

}
