package mill.scalalib.publish

import mill.util.Loose.Agg

import scala.xml.PrettyPrinter

object Ivy {

  val head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

  def apply(
      artifact: Artifact,
      dependencies: Agg[Dependency],
      skipSource: Boolean = false,
      skipDoc: Boolean = false
  ): String = {
    val xml =
      <ivy-module version="2.0" xmlns:e="http://ant.apache.org/ivy/extra">
        <info
        organisation={artifact.group} module={artifact.id} revision={artifact.version} status="release">
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
          { if (skipSource) null else <artifact name={artifact.id} type="src" ext="jar" conf="compile" e:classifier="sources"/> }
          { if (skipDoc) null else <artifact name={artifact.id} type="doc" ext="jar" conf="compile" e:classifier="javadoc"/> }
        </publications>
        <dependencies>{dependencies.map(renderDependency).toSeq}</dependencies>
      </ivy-module>

    val pp = new PrettyPrinter(120, 4)
    head + pp.format(xml).replaceAll("&gt;", ">")
  }

  private def renderDependency(dep: Dependency) = {
    if (dep.exclusions.isEmpty)
      <dependency org={dep.artifact.group} name={dep.artifact.id} rev={dep.artifact.version} conf={s"${scopeToConf(dep.scope)}->${dep.configuration.getOrElse("default(compile)")}"} />
    else
      <dependency org={dep.artifact.group} name={dep.artifact.id} rev={dep.artifact.version} conf={s"${scopeToConf(dep.scope)}->${dep.configuration.getOrElse("default(compile)")}"}>
        {dep.exclusions.map(ex => <exclude org={ex._1} name={ex._2} matcher="exact"/>)}
      </dependency>
  }

  private def scopeToConf(s: Scope): String = s match {
    case Scope.Compile  => "compile"
    case Scope.Provided => "provided"
    case Scope.Test     => "test"
    case Scope.Runtime  => "runtime"
  }

}
