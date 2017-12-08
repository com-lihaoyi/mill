package mill.scalaplugin.publish

trait IvyFile {

  val head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"

  def generateIvy(
    artifact: Artifact,
    dependencies: Seq[Dependency],
    pomSettings: PomSettings
  ): String = {
    val xml = ivyXml(artifact, dependencies, pomSettings)
    head + xml
  }

  // can't use scala-xml
  // it escapes '->' inside dependency conf
  def ivyXml(
    artifact: Artifact,
    dependencies: Seq[Dependency],
    pomSettings: PomSettings
  ): String = {
      val deps = dependencies.map(d => {
        import d.artifact._
        val scope = scopeToConf(d.scope)
        s"""        <dependency org="${group}" name="${id}" rev="${version}" conf="${scope}->default(compile)">
           |        </dependency> """.stripMargin
      }).mkString("\n")
      s"""<ivy-module version="2.0" xmlns:e="http://ant.apache.org/ivy/extra">
         |    <info organisation="${artifact.group}" module="${artifact.id}" revision="${artifact.version}"
         |       status="release">
         |       <description/>
         |     </info>
         |   <configurations>
         |       <conf name="pom" visibility="public" description=""/>
         |       <conf extends="runtime" name="test" visibility="public" description=""/>
         |       <conf name="provided" visibility="public" description=""/>
         |       <conf name="optional" visibility="public" description=""/>
         |       <conf name="compile" visibility="public" description=""/>
         |       <conf extends="compile" name="runtime" visibility="public" description=""/>
         |   </configurations>
         |
         |   <publications>
         |       <artifact name="${artifact.id}" type="pom" ext="pom" conf="pom"/>
         |       <artifact name="${artifact.id}" type="jar" ext="jar" conf="compile"/>
         |   </publications>
         |   <dependencies>
         |${deps}
         |   </dependencies>
         |</ivy-module>
       """.stripMargin
  }

  private def scopeToConf(s: Scope): String = s match {
    case Scope.Compile => "compile"
    case Scope.Provided => "provided"
    case Scope.Test => "test"
    case Scope.Runtime => "runtime"
  }

}

object IvyFile extends IvyFile
