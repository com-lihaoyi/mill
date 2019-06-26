package mill.contrib.bsp

import java.util.Collections

import scala.collection.JavaConverters._
import ch.epfl.scala.bsp4j._
import mill.api.Result.Success
import mill.api.{Loose, Strict}
import mill.define.{Discover, Task}
import mill.eval._
import mill.eval.Evaluator
import mill.scalajslib.ScalaJSModule
import mill.scalalib.api.Util
import mill.scalanativelib._
import mill.scalalib.{JavaModule, ScalaModule, TestModule}
import mill.util.DummyLogger


object ModuleUtils {

  object dummyModule extends mill.define.ExternalModule {
    lazy val millDiscover: Discover[dummyModule.this.type] = Discover[this.type]
  }

  val dummyEvalautor: Evaluator = new Evaluator(os.pwd / "contrib" / "bsp" / "mill-bs",
    os.pwd / "contrib" / "bsp" / "mill-out-bs",
    os.pwd / "contrib" / "bsp" / "mill-external-bs",
    dummyModule, DummyLogger)

    def millModulesToBspTargets(modules: Seq[JavaModule],
                                evaluator: Evaluator,
                                supportedLanguages: List[String]): Predef.Map[JavaModule, BuildTarget] = {

      val moduleIdMap = getModuleTargetIdMap(modules)
      var moduleToTarget = Predef.Map[JavaModule, BuildTarget]()

      for ( module <- modules ) {
        val dataBuildTarget = computeScalaBuildTarget(module, evaluator)
        val capabilities = getModuleCapabilities(module, evaluator)
        val buildTargetTag: String = module match {
          case m: TestModule => BuildTargetTag.TEST
          case m: JavaModule => "-"
        }

        val dependencies = module match {
          case m: JavaModule => m.moduleDeps.map(dep => moduleIdMap(dep)).toList.asJava
        }

        val buildTarget = new BuildTarget(moduleIdMap(module),
          Collections.singletonList(buildTargetTag),
          supportedLanguages.asJava,
          dependencies,
          capabilities)
        buildTarget.setData(dataBuildTarget)
        buildTarget.setDisplayName(module.millModuleSegments.last.value.toList.head.pathSegments.head)
        buildTarget.setBaseDirectory(module.millSourcePath.toNIO.toAbsolutePath.toUri.toString)
        moduleToTarget ++= Map(module -> buildTarget)

      }

      moduleToTarget
    }

  def getModuleCapabilities(module: JavaModule, evaluator: Evaluator): BuildTargetCapabilities = {
    val canRun = getTaskResult(evaluator, module.finalMainClassOpt) match {
      case result: Result.Success[Any] => result.asSuccess.get.value match {
        case _: Right[String, String] => true
        case _: Left[String, String] => false
      }
      case default => false
    }
    val canTest = module match {
      case module: TestModule => true
      case default => false
    }

    new BuildTargetCapabilities(true, canTest, canRun)
  }

  //TODO: I think here I need to look at scalaLibraryIvyDeps, ivyDeps that contain
  // "scala-compiler" and "scala-reflect" and at scalacPluginIvyDeps
  def computeScalaBuildTarget(module: JavaModule, evaluator: Evaluator): Any = {
    module match {
      case m: ScalaModule =>
        val scalaVersion = evaluateInformativeTask(evaluator, m.scalaVersion, "")
        new ScalaBuildTarget(
          evaluateInformativeTask(evaluator, m.scalaOrganization, ""),
          scalaVersion,
          Util.scalaBinaryVersion(scalaVersion),
          getScalaTargetPlatform(m),
          computeScalaLangDependencies(m, evaluator).
            map(pathRef => pathRef.path.toNIO.toAbsolutePath.toUri.toString).
            toList.asJava)

      case m: JavaModule => "This is just a test or java target"
    }
  }

  def getTaskResult[T](evaluator: Evaluator, task: Task[T]): Result[Any] = {
    evaluator.evaluate(Strict.Agg(task)).results(task)
  }

  def evaluateInformativeTask[T](evaluator: Evaluator, task: Task[T], defaultValue: T): T = {
      val evaluated = evaluator.evaluate(Strict.Agg(task)).results(task)
      evaluated match {
        case Success(value) => evaluated.asSuccess.get.value.asInstanceOf[T]
        case default => defaultValue
      }
  }

  def computeScalaLangDependencies(module: ScalaModule, evaluator: Evaluator): Loose.Agg[PathRef] = {
      evaluateInformativeTask(evaluator, module.scalacPluginClasspath, Loose.Agg.empty[PathRef]) ++
      evaluateInformativeTask(evaluator, module.resolveDeps(module.ivyDeps), Loose.Agg.empty[PathRef]).
        filter(pathRef => pathRef.path.toNIO.toAbsolutePath.toUri.toString.contains("scala-compiler") ||
          pathRef.path.toNIO.toAbsolutePath.toUri.toString.contains("scala-reflect") ||
          pathRef.path.toNIO.toAbsolutePath.toUri.toString.contains("scala-library"))
  }

  def getScalaTargetPlatform(module: ScalaModule): ScalaPlatform = {
    module match {
      case m: ScalaNativeModule => ScalaPlatform.NATIVE
      case m: ScalaJSModule => ScalaPlatform.JS
      case m: ScalaModule => ScalaPlatform.JVM
    }
  }

  def getModuleTargetIdMap(modules: Seq[JavaModule]): Predef.Map[JavaModule, BuildTargetIdentifier] = {
    var moduleToTarget = Map[JavaModule, BuildTargetIdentifier]()

    for ( module <- modules ) {
      moduleToTarget ++= Map(module -> new BuildTargetIdentifier(
        module.millSourcePath.toNIO.toAbsolutePath.toUri.toString
      ))
    }

    moduleToTarget
  }
}
