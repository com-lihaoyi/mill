package mill.contrib.bsp

import scala.collection.JavaConverters._
import ch.epfl.scala.bsp4j._
import mill.T
import mill.api.Result.Success
import mill.api.{Loose, Strict}
import mill.define.{BaseModule, Ctx, Segment, Segments, Target, Task}
import mill.eval._
import mill.eval.Evaluator
import mill.scalajslib.ScalaJSModule
import mill.scalalib.api.Util
import mill.scalanativelib._
import mill.scalalib.{JavaModule, ScalaModule, TestModule}
import os.Path

/**
  * Utilities for translating the mill build into
  * BSP information like BuildTargets and BuildTargetIdentifiers
  */
object ModuleUtils {

  /**
    * Compute mapping between all the JavaModules contained in the
    * working directory ( has to be a mill-based project ) and
    * BSP BuildTargets ( mill modules correspond one-to-one to
    * bsp build targets ).
    * @param modules All JavaModules contained in the working
    *                directory of the mill project
    * @param rootModule The root module ( corresponding to the root
    *                   of the mill project )
    * @param evaluator The mill evaluator that can resolve information
    *                  about the mill project
    * @param supportedLanguages the languages supported by the modules
    *                           of the mill project
    * @return JavaModule -> BuildTarget mapping
    */
  def millModulesToBspTargets(modules: Seq[JavaModule],
                                rootModule: JavaModule,
                                evaluator: Evaluator,
                                supportedLanguages: List[String]): Predef.Map[JavaModule, BuildTarget] = {

    val moduleIdMap = getModuleTargetIdMap(modules, evaluator)

    (for ( module <- modules )
      yield (module, getTarget(rootModule, module, evaluator, moduleIdMap))).toMap

  }

  /**
    * Compute the BuildTarget associated with the given module,
    * may or may not be identical to the root of the working
    * directory ( rootModule )
    *
    * @param rootModule  mill JavaModule for the project root
    * @param module      mill JavaModule to compute the BuildTarget
    *                    for
    * @param evaluator   mill Evaluator
    * @param moduleIdMap mapping from each mill JavaModule
    *                    contained in the working directory and
    *                    a BuildTargetIdentifier associated
    *                    with it.
    * @return build target for `module`
    */
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

  /**
    * Given the BaseModule corresponding to the root
    * of the working directory, compute a JavaModule that
    * has the same millSourcePath. Set generated sources
    * accoridng to the location of the compilation
    * products
    * @param rootBaseModule module for the root
    * @return root JavaModule
    */
  def getRootJavaModule(rootBaseModule: BaseModule): JavaModule = {
    implicit val ctx: Ctx = rootBaseModule.millOuterCtx
    new JavaModule {

      override def millSourcePath: Path = rootBaseModule.millSourcePath
      override def sources = T.sources{millSourcePath / "src"}

      def out = T.sources{millSourcePath / "out"}
      def target = T.sources{millSourcePath / "target"}
      override def generatedSources: Target[Seq[PathRef]] = T.sources{
         out() ++ target()}
    }
  }

  /**
    * Compute the BuildTarget associated with the root
    * directory of the mill project being built
    * @param rootModule the root JavaModule extracted from
    *                   the build file by a mill evalautor
    * @param evaluator  mill evaluator that can resolve
    *                   build information
    * @param moduleIdMap mapping from each mill JavaModule
    *                    contained in the working directory and
    *                    a BuildTargetIdentifier associated
    *                    with it.
    * @return root BuildTarget
    */
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
    rootTarget.setBaseDirectory(rootModule.millSourcePath.toIO.toURI.toString)
    rootTarget.setDataKind(BuildTargetDataKind.SCALA)
    rootTarget.setTags(List(BuildTargetTag.LIBRARY, BuildTargetTag.APPLICATION).asJava)
    rootTarget.setData(computeBuildTargetData(rootModule, evaluator))
    val basePath = rootModule.millSourcePath.toIO.toPath
    if (basePath.getNameCount >= 1)
      rootTarget.setDisplayName(basePath.getName(basePath.getNameCount - 1) + "-root")
    else rootTarget.setDisplayName("root")
    rootTarget
  }

  /**
    * Compute the BuildTarget associated with the given mill
    * JavaModule, which is any module present in the working
    * directory, but it's not the root module itself.
    *
    * @param module      any in-project mill module
    * @param evaluator   mill evaluator
    * @param moduleIdMap mapping from each mill JavaModule
    *                    contained in the working directory and
    *                    a BuildTargetIdentifier associated
    *                    with it.
    * @return inner BuildTarget
    */
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
      buildTarget.setDataKind(BuildTargetDataKind.SCALA)
    }
    buildTarget.setData(dataBuildTarget)
    buildTarget.setDisplayName(moduleName(module.millModuleSegments))
    buildTarget.setBaseDirectory(module.intellijModulePath.toIO.toURI.toString)
    buildTarget
  }

  // obtain the capabilities of the given module ( ex: canCompile, canRun, canTest )
  private[this] def getModuleCapabilities(module: JavaModule, evaluator: Evaluator): BuildTargetCapabilities = {
    val canTest = module match {
      case _: TestModule => true
      case default => false
    }

    new BuildTargetCapabilities(true, canTest, true)
  }

  // Compute the ScalaBuildTarget from information about the given JavaModule.
  //TODO: Fix the data field for JavaModule when the bsp specification is updated
  private[this] def computeBuildTargetData(module: JavaModule, evaluator: Evaluator): ScalaBuildTarget = {
    module match {
      case m: ScalaModule =>
        val scalaVersion = evaluateInformativeTask(evaluator, m.scalaVersion, "")
        new ScalaBuildTarget(
          evaluateInformativeTask(evaluator, m.scalaOrganization, ""),
          scalaVersion,
          Util.scalaBinaryVersion(scalaVersion),
          getScalaTargetPlatform(m),
          computeScalaLangDependencies(m, evaluator).
            map(pathRef => pathRef.path.toIO.toURI.toString).
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

  /**
    * Evaluate the given task using the given mill evaluator and return
    * its result of type Result
    * @param evaluator mill evalautor
    * @param task task to evaluate
    * @tparam T
    */
  def getTaskResult[T](evaluator: Evaluator, task: Task[T]): Result[Any] = {
    evaluator.evaluate(Strict.Agg(task)).results(task)
  }

  /**
    * Evaluate the given task using the given mill evaluator and return
    * its result of type T, or the default value of the evaluation failed.
    * @param evaluator mill evalautor
    * @param task task to evaluate
    * @param defaultValue default value to return in case of failure
    * @tparam T
    */
  def evaluateInformativeTask[T](evaluator: Evaluator, task: Task[T], defaultValue: T): T = {
      val evaluated = evaluator.evaluate(Strict.Agg(task)).results(task)
      evaluated match {
        case Success(value) => evaluated.asSuccess.get.value.asInstanceOf[T]
        case default => defaultValue
      }
  }

  // Compute all relevant scala dependencies of `module`, like scala-library, scala-compiler,
  // and scala-reflect
  private[this] def computeScalaLangDependencies(module: ScalaModule, evaluator: Evaluator): Loose.Agg[PathRef] = {
    evaluateInformativeTask(evaluator, module.resolveDeps(module.scalaLibraryIvyDeps), Loose.Agg.empty[PathRef]) ++
      evaluateInformativeTask(evaluator, module.scalacPluginClasspath, Loose.Agg.empty[PathRef]) ++
      evaluateInformativeTask(evaluator, module.resolveDeps(module.ivyDeps), Loose.Agg.empty[PathRef]).
        filter(pathRef => pathRef.path.toIO.toURI.toString.contains("scala-compiler") ||
          pathRef.path.toIO.toURI.toString.contains("scala-reflect") ||
          pathRef.path.toIO.toURI.toString.contains("scala-library"))
  }

  // Obtain the scala platform for `module`
  private[this] def getScalaTargetPlatform(module: ScalaModule): ScalaPlatform = {
    module match {
      case m: ScalaNativeModule => ScalaPlatform.NATIVE
      case m: ScalaJSModule => ScalaPlatform.JS
      case m: ScalaModule => ScalaPlatform.JVM
    }
  }

  /**
    * Compute mapping between a mill JavaModule and the BuildTargetIdentifier
    * associated with its corresponding bsp BuildTarget.
    * @param modules mill modules inside the project ( including root )
    * @param evaluator mill evalautor to resolve build information
    * @return JavaModule -> BuildTargetIdentifier mapping
    */
  def getModuleTargetIdMap(modules: Seq[JavaModule], evaluator:Evaluator): Predef.Map[JavaModule, BuildTargetIdentifier] = {

    (for ( module <- modules )
      yield (module, new BuildTargetIdentifier(
        (module.millOuterCtx.millSourcePath / os.RelPath(moduleName(module.millModuleSegments))).
          toIO.toURI.toString))).toMap
  }

  // this is taken from mill.scalalib GenIdeaImpl
  def moduleName(p: Segments) = p.value.foldLeft(StringBuilder.newBuilder) {
    case (sb, Segment.Label(s)) if sb.isEmpty => sb.append(s)
    case (sb, Segment.Cross(s)) if sb.isEmpty => sb.append(s.mkString("-"))
    case (sb, Segment.Label(s)) => sb.append(".").append(s)
    case (sb, Segment.Cross(s)) => sb.append("-").append(s.mkString("-"))
  }.mkString.toLowerCase()
}
