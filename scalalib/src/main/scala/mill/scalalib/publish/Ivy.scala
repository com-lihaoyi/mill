package mill.scalalib.publish

import mill.util.Loose.OSet

import scala.xml.PrettyPrinter

object Ivy {

  val head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

  def apply(
      artifact: Artifact,
      dependencies: OSet[Dependency]
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
          <artifact name={artifact.id} type="src" ext="jar" conf="compile" e:classifier="sources"/>
          <artifact name={artifact.id} type="doc" ext="jar" conf="compile" e:classifier="javadoc"/>
        </publications>
        <dependencies>{dependencies.map(renderDependency)}</dependencies>
      </ivy-module>

    val pp = new PrettyPrinter(120, 4)
    head + pp.format(xml).replaceAll("&gt;", ">")
  }

  private def renderDependency(dep: Dependency) = {
    val scope = scopeToConf(dep.scope)
    <dependency org={dep.artifact.group} name={dep.artifact.id} rev={dep.artifact.version} conf={s"$scope->default(compile)"}></dependency>
  }

  private def scopeToConf(s: Scope): String = s match {
    case Scope.Compile  => "compile"
    case Scope.Provided => "provided"
    case Scope.Test     => "test"
    case Scope.Runtime  => "runtime"
  }

}
