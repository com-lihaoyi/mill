package mill.script

import mill.*
import mill.api.ExternalModule
import mill.api.Discover
import mill.javalib.*

class JavaModule(val scriptConfig: ScriptModule.Config) extends JavaModule.Base {
  override lazy val millDiscover = Discover[this.type]
}
object JavaModule {
  class TestNg(scriptConfig: ScriptModule.Config) extends JavaModule(scriptConfig) with TestModule.TestNg with mill.javalib.JavaModule.JavaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit4(scriptConfig: ScriptModule.Config) extends JavaModule(scriptConfig) with TestModule.Junit4 with mill.javalib.JavaModule.JavaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit5(scriptConfig: ScriptModule.Config) extends JavaModule(scriptConfig) with TestModule.Junit5 with mill.javalib.JavaModule.JavaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }

  trait Base extends ScriptModule with mill.javalib.JavaModule
    with mill.javalib.NativeImageModule {
    private[mill] def isScript: Boolean = true

    override def moduleDeps = scriptConfig.moduleDeps.map(_.asInstanceOf[mill.javalib.JavaModule])

    override def sources =
      if (os.isDir(scriptConfig.simpleModulePath)) super.sources else Task.Sources()

    /** The script file itself */
    def scriptSource = Task.Source(scriptConfig.simpleModulePath)

    override def allSources =
      if (os.isDir(scriptConfig.simpleModulePath)) super.allSources()
      else sources() ++ Seq(scriptSource())

    /**
     * Whether or not to include the default `mvnDeps` that are bundled with single-file scripts.
     */
    def includDefaultScriptMvnDeps: T[Boolean] = true

    /**
     * The default `mvnDeps` for single-file scripts. For Scala scripts that means MainArgs,
     * uPickle, Requests-Scala, OS-Lib, and PPrint. For Java and Kotlin scripts it is currently
     * empty
     */
    def defaultScriptMvnDeps = Task {
      Seq.empty[Dep]
    }

    override def mandatoryMvnDeps = Task {
      super.mandatoryMvnDeps() ++
        (if (includDefaultScriptMvnDeps()) defaultScriptMvnDeps() else Seq.empty[Dep])
    }
  }
}

class KotlinModule(val scriptConfig: ScriptModule.Config) extends KotlinModule.Base {
  override lazy val millDiscover = Discover[this.type]
}

object KotlinModule {
  class TestNg(scriptConfig: ScriptModule.Config) extends KotlinModule(scriptConfig) with TestModule.TestNg with mill.kotlinlib.KotlinModule.KotlinTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit4(scriptConfig: ScriptModule.Config) extends KotlinModule(scriptConfig) with TestModule.Junit4 with mill.kotlinlib.KotlinModule.KotlinTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit5(scriptConfig: ScriptModule.Config) extends KotlinModule(scriptConfig) with TestModule.Junit5 with mill.kotlinlib.KotlinModule.KotlinTests0 {
    override lazy val millDiscover = Discover[this.type]
  }

  trait Base extends JavaModule.Base, mill.kotlinlib.KotlinModule {
    def kotlinVersion = "2.0.20"
  }
}

class ScalaModule(val scriptConfig: ScriptModule.Config) extends ScalaModule.Base {
  override lazy val millDiscover = Discover[this.type]

  override def defaultScriptMvnDeps = Seq(
    mvn"com.lihaoyi::pprint:${mill.script.BuildInfo.pprintVersion}",
    mvn"com.lihaoyi::os-lib:${mill.script.BuildInfo.osLibVersion}",
    mvn"com.lihaoyi::upickle:${mill.script.BuildInfo.upickleVersion}",
    mvn"com.lihaoyi::requests:${mill.script.BuildInfo.requestsVersion}",
    mvn"com.lihaoyi::mainargs:${mill.script.BuildInfo.mainargsVersion}"
  )
}


object ScalaModule {
  class TestNg(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig) with TestModule.TestNg with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit4(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig) with TestModule.Junit4 with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit5(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig) with TestModule.Junit5 with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class ScalaTest(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig) with TestModule.ScalaTest with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Specs2(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig) with TestModule.Specs2 with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Utest(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig) with TestModule.Utest with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Munit(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig) with TestModule.Munit with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Weaver(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig) with TestModule.Weaver with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class ZioTest(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig) with TestModule.ZioTest with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class ScalaCheck(scriptConfig: ScriptModule.Config) extends ScalaModule(scriptConfig) with TestModule.ScalaCheck with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }

  trait Base extends JavaModule.Base, mill.scalalib.ScalaModule {
    def scalaVersion = mill.util.BuildInfo.scalaVersion
  }

}
