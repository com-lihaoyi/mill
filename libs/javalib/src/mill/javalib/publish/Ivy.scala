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
      val pomInfo = PublishInfo.IvyMetadata.Pom
      if (hasJar)
        Seq(
          pomInfo,
          PublishInfo.IvyMetadata.Jar,
          PublishInfo.IvyMetadata.SourcesJar,
          PublishInfo.IvyMetadata.DocJar
        )
      else
        Seq(pomInfo)
    }

    def renderArtifact(e: PublishInfo.IvyMetadata): Elem = {
      e.classifier match {
        case None =>
          <artifact name={artifact.id} type={e.`type`} ext={e.extension} conf={e.config} />
        case Some(c) =>
          <artifact name={artifact.id} type={e.`type`} ext={e.extension} conf={
            e.config
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
          {(mainPublishInfo ++ extras.iterator.map(_.toIvyMetadata)).map(renderArtifact)}
        </publications>
        <dependencies>
          {dependencies.map(renderDependency)}
          {overrides.map(renderOverride)}
        </dependencies>
      </ivy-module>

    val pp = PrettyPrinter(120, 4)
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
