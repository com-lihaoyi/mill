package mill.scalalib.publish

import mill.api.Loose.Agg

import scala.xml.{Elem, PrettyPrinter}

object Ivy {

  val head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

  case class Override(organization: String, name: String, version: String)

  def apply(
      artifact: Artifact,
      dependencies: Agg[Dependency],
      extras: Seq[PublishInfo] = Seq.empty,
      overrides: Seq[Override] = Nil
  ): String = {

    def renderExtra(e: PublishInfo): Elem = {
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
          <artifact name={artifact.id} type="pom" ext="pom" conf="pom"/>
          <artifact name={artifact.id} type="jar" ext="jar" conf="compile"/>
          <artifact name={artifact.id} type="src" ext="jar" conf="compile" e:classifier="sources"/>
          <artifact name={artifact.id} type="doc" ext="jar" conf="compile" e:classifier="javadoc"/>
          {extras.map(renderExtra)}
        </publications>
        <dependencies>
          {dependencies.map(renderDependency).toSeq}
          {overrides.map(renderOverride)}
        </dependencies>
      </ivy-module>

    val pp = new PrettyPrinter(120, 4)
    head + pp.format(xml).replaceAll("&gt;", ">")
  }

  // bin-compat shim
  def apply(
      artifact: Artifact,
      dependencies: Agg[Dependency],
      extras: Seq[PublishInfo]
  ): String =
    apply(
      artifact,
      dependencies,
      extras,
      Nil
    )

  private def renderDependency(dep: Dependency): Elem = {
    if (dep.exclusions.isEmpty)
      <dependency org={dep.artifact.group} name={dep.artifact.id} rev={dep.artifact.version} conf={
        s"${depIvyConf(dep)}->${dep.configuration.getOrElse("default(compile)")}"
      } />
    else
      <dependency org={dep.artifact.group} name={dep.artifact.id} rev={dep.artifact.version} conf={
        s"${depIvyConf(dep)}->${dep.configuration.getOrElse("default(compile)")}"
      }>
        {dep.exclusions.map(ex => <exclude org={ex._1} name={ex._2} matcher="exact"/>)}
      </dependency>
  }

  private def renderOverride(override0: Override): Elem =
    <override org={override0.organization} module={override0.name} rev={override0.version} />

  private def depIvyConf(d: Dependency): String = {
    if (d.optional) "optional"
    else d.scope match {
      case Scope.Compile => "compile"
      case Scope.Provided => "provided"
      case Scope.Test => "test"
      case Scope.Runtime => "runtime"
    }
  }

}
