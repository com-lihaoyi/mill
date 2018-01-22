import mill._, scalalib._
val binCrossScalaVersions = Seq("2.11.11", "2.12.4")
val fullCrossScalaVersions = Seq(
  "2.11.3", "2.11.4", "2.11.5", "2.11.6", "2.11.7", "2.11.8", "2.11.9", "2.11.11",
  "2.12.0", "2.12.1", "2.12.2", "2.12.3", "2.12.4"
)
trait AmmModule extends mill.scalalib.CrossSbtModule{
  def testFramework = "utest.runner.Framework"
  def scalacOptions = Seq("-P:acyclic:force", "-target:jvm-1.7")
  def compileIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.1.7")
  def scalacPluginIvyDeps = Agg(ivy"com.lihaoyi::acyclic:0.1.7")
  trait Tests extends super.Tests{
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.6.0")
    def testFramework = "utest.runner.Framework"
  }
}

object ops extends Cross[OpsModule](binCrossScalaVersions:_*)
class OpsModule(val crossScalaVersion: String) extends AmmModule{
  def ivyDeps = Agg(ivy"com.lihaoyi::geny:0.1.2")

  object test extends Tests
}

object terminal extends Cross[TerminalModule](binCrossScalaVersions:_*)
class TerminalModule(val crossScalaVersion: String) extends AmmModule{
  def ivyDeps = Agg(
    ivy"com.lihaoyi::sourcecode:0.1.3",
    ivy"com.lihaoyi::fansi:0.2.4"
  )
  def compileIvyDeps = Agg(
    ivy"org.scala-lang:scala-reflect:$crossScalaVersion"
  )

  object test extends Tests
}

object amm extends Cross[MainModule](fullCrossScalaVersions:_*){
  object util extends Cross[UtilModule](binCrossScalaVersions:_*)
  class UtilModule(val crossScalaVersion: String) extends AmmModule{
    def moduleDeps = Seq(ops())
    def ivyDeps = Agg(
      ivy"com.lihaoyi::upickle:0.5.1",
      ivy"com.lihaoyi::pprint:0.5.2",
      ivy"com.lihaoyi::fansi:0.2.4"
    )
  }


  object runtime extends Cross[RuntimeModule](binCrossScalaVersions:_*)
  class RuntimeModule(val crossScalaVersion: String) extends AmmModule{
    def moduleDeps = Seq(ops(), amm.util())
    def ivyDeps = Agg(
      ivy"io.get-coursier::coursier:1.0.0",
      ivy"io.get-coursier::coursier-cache:1.0.0",
      ivy"org.scalaj::scalaj-http:2.3.0"
    )

    def generatedSources = T{
      import ammonite.ops._
      mkdir(T.ctx().dest)
      cp(build.basePath/'project/"Constants.scala", T.ctx().dest/"Constants.scala")
      Seq(PathRef(T.ctx().dest))
    }
  }

  object interp extends Cross[InterpModule](fullCrossScalaVersions:_*)
  class InterpModule(val crossScalaVersion: String) extends AmmModule{
    def moduleDeps = Seq(ops(), amm.util(), amm.runtime())
    def ivyDeps = Agg(
      ivy"org.scala-lang:scala-compiler:$crossScalaVersion",
      ivy"org.scala-lang:scala-reflect:$crossScalaVersion",
      ivy"com.lihaoyi::scalaparse:1.0.0",
      ivy"org.javassist:javassist:3.21.0-GA"
    )
  }

  object repl extends Cross[ReplModule](fullCrossScalaVersions:_*)
  class ReplModule(val crossScalaVersion: String) extends AmmModule{
    def moduleDeps = Seq(
      ops(), amm.util(),
      amm.runtime(), amm.interp(),
      terminal()
    )
    def ivyDeps = Agg(
      ivy"jline:jline:2.14.3",
      ivy"com.github.javaparser:javaparser-core:3.2.5",
      ivy"com.github.scopt::scopt:3.5.0"
    )

    object test extends Tests{
      def resources = T.input {
        super.resources() ++
          ReplModule.this.sources() ++
          ReplModule.this.externalCompileDepSources()
      }
    }
  }
}
class MainModule(val crossScalaVersion: String) extends AmmModule{

  def mainClass = Some("ammonite.Main")

  def moduleDeps = Seq(
    terminal(), ops(),
    amm.util(), amm.runtime(),
    amm.interp(), amm.repl()
  )
  def ivyDeps = Agg(
    ivy"com.github.scopt::scopt:3.5.0",
  )

  def runClasspath =
    super.runClasspath() ++
      ops().sources() ++
      terminal().sources() ++
      amm.util().sources() ++
      amm.runtime().sources() ++
      amm.interp().sources() ++
      amm.repl().sources() ++
      sources() ++
      externalCompileDepSources()



  def prependShellScript =
    "#!/usr/bin/env sh\n" +
      """exec java -jar -Xmx500m -XX:+UseG1GC $JAVA_OPTS "$0" "$@""""


  object test extends Tests{
    def moduleDeps = super.moduleDeps ++ Seq(amm.repl().test)
    def ivyDeps = super.ivyDeps() ++ Agg(
      ivy"com.chuusai::shapeless:2.3.2"
    )
  }
}

object shell extends Cross[ShellModule](fullCrossScalaVersions:_*)
class ShellModule(val crossScalaVersion: String) extends AmmModule{
  def moduleDeps = Seq(ops(), amm())
  object test extends Tests{
    def moduleDeps = super.moduleDeps ++ Seq(amm.repl().test)
    def forkEnv = super.forkEnv() ++ Seq(
      "AMMONITE_TEST_SHELL" -> shell().jar().path.toString,
      "AMMONITE_TEST_ASSEMBLY" -> amm().assembly().path.toString
    )
  }
}
object integration extends Cross[IntegrationModule](fullCrossScalaVersions:_*)
class IntegrationModule(val crossScalaVersion: String) extends AmmModule{
  def moduleDeps = Seq(ops(), amm())
  //  (test in Test) := (test in Test).dependsOn(integrationTasks:_*).value,
  //  (run in Test) := (run in Test).dependsOn(integrationTasks:_*).evaluated,
  //  (testOnly in Test) := (testOnly in Test).dependsOn(integrationTasks:_*).evaluated,
  //  (console in Test) := (console in Test).dependsOn(integrationTasks:_*).value,
  //  initialCommands in (Test, console) := "ammonite.integration.Main.main(null)"
  object test extends Tests {
    def forkEnv = super.forkEnv() ++ Seq(
      "AMMONITE_TEST_SHELL" -> shell().jar().path.toString,
      "AMMONITE_TEST_ASSEMBLY" -> amm().assembly().path.toString
    )
  }
}

object sshd extends Cross[SshdModule](fullCrossScalaVersions:_*)
class SshdModule(val crossScalaVersion: String) extends AmmModule{
  def moduleDeps = Seq(ops(), amm())
  def ivyDeps = Agg(
    // sshd-core 1.3.0 requires java8
    ivy"org.apache.sshd:sshd-core:1.2.0",
    ivy"org.bouncycastle:bcprov-jdk15on:1.56",
  )
  object test extends Tests {
    def ivyDeps = super.ivyDeps() ++ Agg(
      // slf4j-nop makes sshd server use logger that writes into the void
      ivy"org.slf4j:slf4j-nop:1.7.12",
      ivy"com.jcraft:jsch:0.1.54",
      ivy"org.scalacheck::scalacheck:1.12.6"
    )
  }
}