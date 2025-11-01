package mill.script
import mill.*
import mill.api.{Discover, ExternalModule, PathRef}
import mill.javalib.{TestModule, DepSyntax, Dep}
import mill.javalib.api.CompilationResult
import mill.util.Jvm

class ScalaModule(scriptConfig: ScriptModule.Config) extends ScalaModule.Raw(scriptConfig) {
  override lazy val millDiscover = Discover[this.type]

  override def mandatoryMvnDeps = super.mandatoryMvnDeps() ++ Seq(
    mvn"com.lihaoyi::pprint:${mill.script.BuildInfo.pprintVersion}",
    mvn"com.lihaoyi::os-lib:${mill.script.BuildInfo.osLibVersion}",
    mvn"com.lihaoyi::upickle:${mill.script.BuildInfo.upickleVersion}",
    mvn"com.lihaoyi::requests:${mill.script.BuildInfo.requestsVersion}",
    mvn"com.lihaoyi::mainargs:${mill.script.BuildInfo.mainargsVersion}"
  )

  override def allSourceFiles = Task {
    val original = scriptSource().path
    val modified = Task.dest / original.last
    os.write(
      modified,
      os.read(original) +
        System.lineSeparator +
        // Squeeze this onto one line so as not to affect line counts too much
        """type main = mainargs.main; def _millScriptMainSelf = this; object _MillScriptMain { def main(args: Array[String]): Unit = this.getClass.getMethods.find(m => m.getName == "main" && m.getParameters.map(_.getType) == Seq(classOf[Array[String]]) && m.getReturnType == classOf[Unit]) match{ case Some(m) => m.invoke(_millScriptMainSelf, args); case None => mainargs.Parser(_millScriptMainSelf).runOrExit(args) }}""".stripMargin
    )

    Seq(PathRef(modified))
  }

  private def asmWorkerClasspath: T[Seq[PathRef]] = Task {
    defaultResolver().classpath(Seq(Dep.millProjectModule("mill-libs-script-asm-worker")))
  }

  private def asmWorkerClassloader: Task.Worker[ClassLoader] = Task.Worker {
    Jvm.createClassLoader(classPath = asmWorkerClasspath().map(_.path), parent = null)
  }

  override def compile: T[CompilationResult] = Task {
    val result = super.compile()

    val classesDir = Task.dest / "classes"
    os.copy(result.classes.path, classesDir, createFolders = true)

    asmWorkerClassloader()
      .loadClass("mill.script.asm.AsmWorkerImpl")
      .getMethod("generateSyntheticClasses", classOf[java.nio.file.Path], classOf[Array[String]])
      .invoke(null, classesDir.toNIO, syntheticMainargsMainClasses().toArray)

    CompilationResult(result.analysisFile, PathRef(classesDir))
  }

  private def syntheticMainargsMainClasses = Task {
    asmWorkerClassloader()
      .loadClass("mill.script.asm.AsmWorkerImpl")
      .getMethod("findMainArgsMethods", classOf[java.nio.file.Path])
      .invoke(null, super.compile().classes.path.toNIO)
      .asInstanceOf[Array[String]]
      .toSeq
  }

  override def allLocalMainClasses = Task {
    // Never consider the `syntheticMainargsMainClasses` when choosing a default main class,
    // since those are purely for compatibility when run directly via `runMain`
    //
    // If multiple main methods are present, skip the synthetic `_MillScriptMain` one
    // and use the user-provided main method instead
    super.allLocalMainClasses().filter(!syntheticMainargsMainClasses().contains(_)) match {
      case Seq(single) => Seq(single)
      case multiple => multiple.filter(!_.endsWith("_MillScriptMain"))
    }
  }
}

object ScalaModule {
  class Raw(val scriptConfig: ScriptModule.Config) extends ScalaModule.Base {
    override lazy val millDiscover = Discover[this.type]
  }
  class TestNg(scriptConfig: ScriptModule.Config) extends ScalaModule.Raw(scriptConfig)
      with TestModule.TestNg with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit4(scriptConfig: ScriptModule.Config) extends ScalaModule.Raw(scriptConfig)
      with TestModule.Junit4 with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Junit5(scriptConfig: ScriptModule.Config) extends ScalaModule.Raw(scriptConfig)
      with TestModule.Junit5 with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class ScalaTest(scriptConfig: ScriptModule.Config) extends ScalaModule.Raw(scriptConfig)
      with TestModule.ScalaTest with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Specs2(scriptConfig: ScriptModule.Config) extends ScalaModule.Raw(scriptConfig)
      with TestModule.Specs2 with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Utest(scriptConfig: ScriptModule.Config) extends ScalaModule.Raw(scriptConfig)
      with TestModule.Utest with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Munit(scriptConfig: ScriptModule.Config) extends ScalaModule.Raw(scriptConfig)
      with TestModule.Munit with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class Weaver(scriptConfig: ScriptModule.Config) extends ScalaModule.Raw(scriptConfig)
      with TestModule.Weaver with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class ZioTest(scriptConfig: ScriptModule.Config) extends ScalaModule.Raw(scriptConfig)
      with TestModule.ZioTest with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }
  class ScalaCheck(scriptConfig: ScriptModule.Config) extends ScalaModule.Raw(scriptConfig)
      with TestModule.ScalaCheck with mill.scalalib.ScalaModule.ScalaTests0 {
    override lazy val millDiscover = Discover[this.type]
  }

  trait Base extends JavaModule.Base, mill.scalalib.ScalaModule {
    def scalaVersion = mill.util.BuildInfo.scalaVersion
  }

}
