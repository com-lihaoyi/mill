package mill.simple

import mill.api.{Discover, ModuleRef}
import mill.*
import mill.javalib.*
class JavaModule(val simpleConf: SimpleModule.Config) extends mill.simple.JavaModule.Base {
  override lazy val millDiscover = Discover[this.type]
}
object JavaModule {
  trait Base extends SimpleModule with mill.javalib.JavaModule with mill.javalib.NativeImageModule {
    override def moduleDeps = simpleConf.moduleDeps.map(_.asInstanceOf[mill.javalib.JavaModule])

    override def sources =
      if (os.isDir(simpleConf.simpleModulePath)) super.sources else Task.Sources()

    def scriptSource = Task.Source(simpleConf.simpleModulePath)

    override def allSources = {
      if (os.isDir(simpleConf.simpleModulePath)) super.allSources
      else Task {
        sources() ++ Seq(scriptSource())
      }
    }
  }

  class Publish(val simpleConf: SimpleModule.Config) extends mill.simple.JavaModule.Base,
        PublishModule {
    override lazy val millDiscover = Discover[this.type]

    def pomSettings = Task {
      ???
    }

    def publishVersion = Task {
      ???
    }

  }

  trait Test0 extends JavaModule.Base, mill.javalib.JavaModule.Tests {
    def outerRef = ModuleRef(simpleConf.moduleDeps.head.asInstanceOf[mill.javalib.JavaModule])
  }

  class TestNg(val simpleConf: SimpleModule.Config) extends Test0, TestModule.TestNg {
    override lazy val millDiscover = Discover[this.type]
  }

  class Junit4(val simpleConf: SimpleModule.Config) extends Test0, TestModule.Junit4 {
    override lazy val millDiscover = Discover[this.type]
  }

  class Junit5(val simpleConf: SimpleModule.Config) extends Test0, TestModule.Junit5 {
    override lazy val millDiscover = Discover[this.type]
  }
}
