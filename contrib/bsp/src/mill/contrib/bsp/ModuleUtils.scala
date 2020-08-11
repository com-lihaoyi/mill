package mill.contrib.bsp

import ch.epfl.scala.bsp4j._
import java.net.URL
import mill._
import mill.api.Result.Success
import mill.api.{PathRef, Strict}
import mill.define._
import mill.eval.{Evaluator, _}
import mill.scalajslib.ScalaJSModule
import mill.scalalib.Lib.{depToDependency, resolveDependencies, scalaRuntimeIvyDeps}
import mill.scalalib.api.Util
import mill.scalalib.{JavaModule, ScalaModule, TestModule}
import mill.scalanativelib._
import mill.util.Ctx
import scala.collection.JavaConverters._

/**
 * Utilities for translating the mill build into
 * BSP information like BuildTargets and BuildTargetIdentifiers
 */
object ModuleUtils {

  /**
   * Resolve all the mill modules contained in the project
   * */
  def getModules(evaluator: Evaluator): Seq[JavaModule] =
    evaluator.rootModule.millInternal.segmentsToModules.values.collect {
      case m: JavaModule => m
    }.toSeq

  /**
   * Resolve a mill modules given a target identifier
   * */
  def getModule(targetId: BuildTargetIdentifier, modules: Seq[JavaModule]): JavaModule =
    modules
      .find(getTargetId(_) == targetId)
      .getOrElse(throw new IllegalArgumentException(s"No module found for target id ${targetId.getUri}"))

  /**
   * Compute mapping between all the JavaModules contained in the
   * working directory ( has to be a mill-based project ) and
   * BSP BuildTargets ( mill modules correspond one-to-one to
   * bsp build targets ).
   *
    * @param modules            All JavaModules contained in the working
   *                           directory of the mill project
   * @param evaluator          The mill evaluator that can resolve information
   *                           about the mill project
   * @return JavaModule -> BuildTarget mapping
   */
  def getTargets(modules: Seq[JavaModule], evaluator: Evaluator)(implicit ctx: Ctx.Log): Seq[BuildTarget] = {
    val targets = modules.map(module => getTarget(module, evaluator))
    val millBuildTarget = getMillBuildTarget(evaluator, modules)

    millBuildTarget +: targets
  }

  def getMillBuildTargetId(evaluator: Evaluator): BuildTargetIdentifier =
    new BuildTargetIdentifier(evaluator.rootModule.millSourcePath.toNIO.toUri.toString)

  /**
   * Compute the BuildTarget for the Mill build (build.sc files)
   *
   * @param evaluator   mill evaluator that can resolve
   *                    build information
   * @return the Mill BuildTarget
   */
  def getMillBuildTarget(evaluator: Evaluator, modules: Seq[JavaModule])(implicit ctx: Ctx.Log): BuildTarget = {
    val target = new BuildTarget(
      getMillBuildTargetId(evaluator),
      Seq.empty[String].asJava,
      Seq("scala").asJava,
      Seq.empty[BuildTargetIdentifier].asJava,
      new BuildTargetCapabilities(false, false, false)
    )
    target.setBaseDirectory(evaluator.rootModule.millSourcePath.toNIO.toUri.toString)
    target.setDataKind(BuildTargetDataKind.SCALA)
    target.setTags(Seq(BuildTargetTag.LIBRARY, BuildTargetTag.APPLICATION).asJava)
    target.setDisplayName("mill-build")

    val scalaOrganization = "org.scala-lang"
    val scalaLibDep = scalaRuntimeIvyDeps(scalaOrganization, BuildInfo.scalaVersion)
    val classpath = resolveDependencies(
      modules.flatMap(_.repositories).distinct,
      depToDependency(_, BuildInfo.scalaVersion),
      scalaLibDep,
      ctx = Some(ctx)
    ).asSuccess.toSeq.flatMap(_.value)

    target.setData(
      new ScalaBuildTarget(
        scalaOrganization,
        BuildInfo.scalaVersion,
        Util.scalaBinaryVersion(BuildInfo.scalaVersion),
        ScalaPlatform.JVM,
        classpath.map(_.path.toNIO.toUri.toString).asJava
      )
    )

    target
  }

  /**
   * Compute the BuildTarget associated with the given mill
   * JavaModule, which is any module present in the working
   * directory, but it's not the root module itself.
   *
    * @param module      any in-project mill module
   * @param evaluator   mill evaluator
   * @return inner BuildTarget
   */
  def getTarget(module: JavaModule, evaluator: Evaluator): BuildTarget = {
    val dataBuildTarget = computeBuildTargetData(module, evaluator)
    val capabilities = getModuleCapabilities(module)
    val buildTargetTag = module match {
      case _: TestModule => Seq(BuildTargetTag.TEST)
      case _: JavaModule => Seq(BuildTargetTag.LIBRARY, BuildTargetTag.APPLICATION)
    }

    val buildTarget = new BuildTarget(
      getTargetId(module),
      buildTargetTag.asJava,
      Seq("scala", "java").asJava,
      module.moduleDeps.map(getTargetId).toList.asJava,
      capabilities
    )
    if (module.isInstanceOf[ScalaModule]) {
      buildTarget.setDataKind(BuildTargetDataKind.SCALA)
    }
    buildTarget.setData(dataBuildTarget)
    buildTarget.setDisplayName(module.millModuleSegments.render)
    buildTarget
  }

  // obtain the capabilities of the given module ( ex: canCompile, canRun, canTest )
  private[this] def getModuleCapabilities(module: JavaModule): BuildTargetCapabilities = {
    val canTest = module match {
      case _: TestModule => true
      case _ => false
    }

    new BuildTargetCapabilities(true, canTest, true)
  }

  /**
   * Evaluate the given task using the given mill evaluator and return
   * its result of type Result
   *
    * @param evaluator mill evalautor
   * @param task      task to evaluate
   * @tparam T
   */
  def getTaskResult[T](evaluator: Evaluator, task: Task[T]): Result[Any] = {
    evaluator.evaluate(Strict.Agg(task)).results(task)
  }

  /**
   * Evaluate the given task using the given mill evaluator and return
   * its result of type T, or the default value of the evaluation failed.
   *
    * @param evaluator    mill evalautor
   * @param task         task to evaluate
   * @param defaultValue default value to return in case of failure
   * @tparam T
   */
  def evaluateInformativeTask[T](evaluator: Evaluator, task: Task[T], defaultValue: T): T = {
    val evaluated = evaluator.evaluate(Strict.Agg(task)).results(task)
    evaluated match {
      case Success(_) => evaluated.asSuccess.get.value.asInstanceOf[T]
      case _ => defaultValue
    }
  }

  def getTargetId(module: JavaModule): BuildTargetIdentifier =
    new BuildTargetIdentifier(
      (module.millOuterCtx.millSourcePath / module.millModuleSegments.parts).toNIO.toUri.toString
    )

  def getTarget(moduleHashCode: Int, modules: Seq[JavaModule], evaluator: Evaluator): Option[BuildTarget] =
    modules.find(_.hashCode == moduleHashCode).map(getTarget(_, evaluator))

  def getTargetId(moduleHashCode: Int, modules: Seq[JavaModule]): Option[BuildTargetIdentifier] =
    modules.find(_.hashCode == moduleHashCode).map(getTargetId)

  def isSourceJar(url: URL): Boolean = url.getFile.endsWith("-sources.jar")

  // Compute the ScalaBuildTarget from information about the given JavaModule.
  private[this] def computeBuildTargetData(module: JavaModule, evaluator: Evaluator): ScalaBuildTarget = {
    module match {
      case m: ScalaModule =>
        val scalaVersion = evaluateInformativeTask(evaluator, m.scalaVersion, "")
        new ScalaBuildTarget(
          evaluateInformativeTask(evaluator, m.scalaOrganization, ""),
          scalaVersion,
          Util.scalaBinaryVersion(scalaVersion),
          getScalaTargetPlatform(m),
          computeScalaLangDependencies(m, evaluator)
            .map(_.path.toNIO.toUri.toString)
            .iterator
            .toSeq
            .asJava
        )
      case _: JavaModule =>
        new ScalaBuildTarget(
          "org.scala-lang",
          BuildInfo.scalaVersion,
          Util.scalaBinaryVersion(BuildInfo.scalaVersion),
          ScalaPlatform.JVM,
          Seq.empty[String].asJava
        )
      case m =>
        throw new IllegalStateException(s"Module type of ${m.millModuleSegments.render} not supported by BSP")
    }
  }

  // Compute all relevant scala dependencies of `module`, like scala-library, scala-compiler,
  // and scala-reflect
  private[this] def computeScalaLangDependencies(module: ScalaModule, evaluator: Evaluator): Agg[PathRef] = {
    evaluateInformativeTask(evaluator, module.resolveDeps(module.scalaLibraryIvyDeps), Agg.empty[PathRef]) ++
      evaluateInformativeTask(evaluator, module.scalacPluginClasspath, Agg.empty[PathRef]) ++
      evaluateInformativeTask(evaluator, module.resolveDeps(module.ivyDeps), Agg.empty[PathRef]).filter(pathRef =>
        pathRef.path.toNIO.toUri.toString.contains("scala-compiler") ||
          pathRef.path.toNIO.toUri.toString.contains("scala-reflect") ||
          pathRef.path.toNIO.toUri.toString.contains("scala-library")
      )
  }

  // Obtain the scala platform for `module`
  private[this] def getScalaTargetPlatform(module: ScalaModule): ScalaPlatform = {
    module match {
      case _: ScalaNativeModule => ScalaPlatform.NATIVE
      case _: ScalaJSModule => ScalaPlatform.JS
      case _: ScalaModule => ScalaPlatform.JVM
    }
  }
}
