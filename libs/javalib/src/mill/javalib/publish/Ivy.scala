package mill.javalib.publish

import scala.xml.{Elem, PrettyPrinter}

/**
 * Logic around rendering `ivy.xml` files
 */
object Ivy {

  val head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

  case class Override(organization: String, name: String, version: String)

  /**
   * Generates the content of an ivy.xml file
   *
   * @param artifact Coordinates of the module
   * @param dependencies Dependencies of the module
   * @param extras Extra artifacts published alongside the module
   * @param overrides Version overrides
   * @param hasJar Whether the module has a JAR
   * @return ivy.xml content
   */
  def apply(
      artifact: Artifact,
      dependencies: Seq[Dependency],
      extras: Seq[PublishInfo] = Seq.empty,
      overrides: Seq[Override] = Nil,
      hasJar: Boolean = true
  ): String = {

    val mainPublishInfo = {
      val pomInfo = PublishInfo(null, ivyType = "pom", ext = "pom", ivyConfig = "pom")
      if (hasJar)
        Seq(
          pomInfo,
          PublishInfo.jar(null),
          PublishInfo.sourcesJar(null),
          PublishInfo.docJar(null)
        )
      else
        Seq(pomInfo)
    }

    def renderArtifact(e: PublishInfo): Elem = {
      e.classifier match {
        case None =>
          <artifact name={artifact.id} type={e.ivyType} ext={e.ext} conf={e.ivyConfig} />
        case Some(c) =>
          <artifact name={artifact.id} type={e.ivyType} ext={e.ext} conf={
            e.ivyConfig
          } e:classifier={c} />
      }
    }

    val xml =
      <ivy-module version="2.0" xmlns:e="http://ant.apache.org/ivy/extra">
        <info
        organisation={artifact.group} module={artifact.id} revision={
        artifact.version
      } status="release">
          <description/>
        </info>
        <configurations>
          <conf name="pom" visibility="public" description=""/>
          <conf extends="runtime" name="test" visibility="public" description=""/>
          <conf name="provided" visibility="public" description=""/>
          <conf name="optional" visibility="public" description=""/>
          <conf name="compile" visibility="public" description=""/>
          <conf extends="compile" name="runtime" visibility="public" description=""/>
        </configurations>

        <publications>
          {(mainPublishInfo ++ extras).map(renderArtifact)}
        </publications>
        <dependencies>
          {dependencies.map(renderDependency).toSeq}
          {overrides.map(renderOverride)}
        </dependencies>
      </ivy-module>

    val pp = new PrettyPrinter(120, 4)
    head + pp.format(xml).replaceAll("&gt;", ">")
  }

  private def renderDependency(dep: Dependency): Elem = {
    if (dep.exclusions.isEmpty)
      <dependency org={dep.artifact.group} name={dep.artifact.id} rev={dep.artifact.version} conf={
        depIvyConf(dep)
      } />
    else
      <dependency org={dep.artifact.group} name={dep.artifact.id} rev={dep.artifact.version} conf={
        depIvyConf(dep)
      }>
        {dep.exclusions.map(ex => <exclude org={ex._1} name={ex._2} matcher="exact"/>)}
      </dependency>
  }

  private def renderOverride(override0: Override): Elem =
    <override org={override0.organization} module={override0.name} rev={override0.version} />

  private def depIvyConf(d: Dependency): String = {
    def target(value: String) = d.configuration.getOrElse(value)
    if (d.optional) s"optional->${target("runtime")}"
    else d.scope match {
      case Scope.Compile => s"compile->${target("compile")};runtime->${target("runtime")}"
      case Scope.Provided => s"provided->${target("compile")}"
      case Scope.Test => s"test->${target("runtime")}"
      case Scope.Runtime => s"runtime->${target("runtime")}"
      case Scope.Import => ???
    }
  }

}
