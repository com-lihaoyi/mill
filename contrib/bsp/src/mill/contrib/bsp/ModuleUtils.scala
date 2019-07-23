package mill.contrib.bsp

import java.net.URI
import java.util.Collections

import scala.collection.JavaConverters._
import ch.epfl.scala.bsp4j._
import mill.T
import mill.api.Result.Success
import mill.api.{Loose, Strict}
import mill.define.{BaseModule, Ctx, Discover, Module, Segment, Segments, Sources, Target, Task}
import mill.eval._
import mill.eval.Evaluator
import mill.scalajslib.ScalaJSModule
import mill.scalalib.api.Util
import mill.scalanativelib._
import mill.scalalib.{CrossModuleBase, GenIdea, GenIdeaImpl, JavaModule, ScalaModule, TestModule}
import mill.util.DummyLogger
import os.Path


object ModuleUtils {

    def millModulesToBspTargets(modules: Seq[JavaModule],
                                rootModule: JavaModule,
                                evaluator: Evaluator,
                                supportedLanguages: List[String]): Predef.Map[JavaModule, BuildTarget] = {

      val moduleIdMap = getModuleTargetIdMap(modules, evaluator)

      (for ( module <- modules )
        yield (module, getTarget(rootModule, module, evaluator, moduleIdMap))).toMap

    }

  def getRootJavaModule(rootBaseModule: BaseModule): JavaModule = {
    implicit val ctx: Ctx = rootBaseModule.millOuterCtx
    new JavaModule {

      override def millSourcePath: Path = rootBaseModule.millSourcePath
      override def sources = T.sources{millSourcePath / "src"}

      def out = T.sources{millSourcePath / "out"}
      def target = T.sources{millSourcePath / "target"}
      //override def sources: Sources = T.sources{millSourcePath}
      override def generatedSources: Target[Seq[PathRef]] = T.sources{
         out() ++ target()}
    }
  }

  def getRootTarget(
                     rootModule: JavaModule,
                     evaluator: Evaluator,
                     moduleIdMap: Map[JavaModule, BuildTargetIdentifier]): BuildTarget = {

    val rootTarget = new BuildTarget(
      moduleIdMap(rootModule),
      List.empty[String].asJava,
      List.empty[String].asJava,
      List.empty[BuildTargetIdentifier].asJava,
      new BuildTargetCapabilities(false, false, false))
    rootTarget.setBaseDirectory(rootModule.millSourcePath.toNIO.toAbsolutePath.toUri.toString)
    rootTarget.setDataKind("scala")
    rootTarget.setTags(List(BuildTargetTag.LIBRARY, BuildTargetTag.APPLICATION).asJava)
    rootTarget.setData(computeBuildTargetData(rootModule, evaluator))
    val basePath = rootModule.millSourcePath.toIO.toPath
    if (basePath.getNameCount >= 1)
      rootTarget.setDisplayName(basePath.getName(basePath.getNameCount - 1) + "-root")
    else rootTarget.setDisplayName("root")
    rootTarget
  }

  def getRegularTarget(
                 module: JavaModule,
                 evaluator: Evaluator,
                 moduleIdMap: Map[JavaModule, BuildTargetIdentifier]): BuildTarget = {
    val dataBuildTarget = computeBuildTargetData(module, evaluator)
    val capabilities = getModuleCapabilities(module, evaluator)
    val buildTargetTag: List[String] = module match {
      case m: TestModule => List(BuildTargetTag.TEST)
      case m: JavaModule => List(BuildTargetTag.LIBRARY, BuildTargetTag.APPLICATION)
    }

    val dependencies = module match {
      case m: JavaModule => m.moduleDeps.map(dep => moduleIdMap(dep)).toList.asJava
    }

    val buildTarget = new BuildTarget(moduleIdMap(module),
      buildTargetTag.asJava,
      List("scala", "java").asJava,
      dependencies,
      capabilities)
    if (module.isInstanceOf[ScalaModule]) {
      buildTarget.setDataKind("scala")
    }
    buildTarget.setData(dataBuildTarget)
    buildTarget.setDisplayName(moduleName(module.millModuleSegments))
    buildTarget.setBaseDirectory(module.intellijModulePath.toNIO.toAbsolutePath.toUri.toString)
    buildTarget
  }

  def getTarget( rootModule: JavaModule,
                 module: JavaModule,
                 evaluator: Evaluator,
                 moduleIdMap: Map[JavaModule, BuildTargetIdentifier]
               ): BuildTarget = {
    if (module == rootModule)
      getRootTarget(module, evaluator, moduleIdMap)
    else
      getRegularTarget(module, evaluator, moduleIdMap)
  }

  def getModuleCapabilities(module: JavaModule, evaluator: Evaluator): BuildTargetCapabilities = {
    val canTest = module match {
      case module: TestModule => true
      case default => false
    }

    new BuildTargetCapabilities(true, canTest, true)
  }

  //TODO: Fix the data field for JavaModule when the bsp specification is updated
  def computeBuildTargetData(module: JavaModule, evaluator: Evaluator): ScalaBuildTarget = {
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

      case m: JavaModule =>
        val scalaVersion = "2.12.8"
        new ScalaBuildTarget(
          "or.scala-lang",
          "2.12.8",
          "2.12",
          ScalaPlatform.JVM,
          List.empty[String].asJava)
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
    evaluateInformativeTask(evaluator, module.resolveDeps(module.scalaLibraryIvyDeps), Loose.Agg.empty[PathRef]) ++
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

  def getModuleTargetIdMap(modules: Seq[JavaModule], evaluator:Evaluator): Predef.Map[JavaModule, BuildTargetIdentifier] = {

    (for ( module <- modules )
      yield (module, new BuildTargetIdentifier(
        (module.millOuterCtx.millSourcePath / os.RelPath(moduleName(module.millModuleSegments))).
          toNIO.toAbsolutePath.toUri.toString))).toMap
  }

  // this is taken from mill.scalalib GenIdeaImpl
  def moduleName(p: Segments) = p.value.foldLeft(StringBuilder.newBuilder) {
    case (sb, Segment.Label(s)) if sb.isEmpty => sb.append(s)
    case (sb, Segment.Cross(s)) if sb.isEmpty => sb.append(s.mkString("-"))
    case (sb, Segment.Label(s)) => sb.append(".").append(s)
    case (sb, Segment.Cross(s)) => sb.append("-").append(s.mkString("-"))
  }.mkString.toLowerCase()
}
