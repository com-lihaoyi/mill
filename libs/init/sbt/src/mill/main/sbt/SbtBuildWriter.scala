package mill.main.sbt

import mill.constants.CodeGenConstants.rootModuleAlias
import mill.main.buildgen.*
import pprint.Util.literalize

import java.io.PrintStream

object SbtBuildWriter extends BuildWriter {

  def computeImports(pkg: Tree[ModuleRepr]) = {
    val b = Set.newBuilder[String]
    b += "mill.scalalib._"
    for module <- pkg.iterator do
      if (module.configs.isEmpty || module.crossConfigs.nonEmpty) b += "mill.api._"
      if (module.testModule.exists(_.supertypes.exists(_.startsWith("TestModule"))))
        b += "mill.javalib._"
      module.configs.foreach:
        case _: ScalaJSModuleConfig => b += "mill.scalajslib._"
        case _: ScalaNativeModuleConfig => b += "mill.scalanativelib._"
        case _: ScalaModuleConfig => b += "mill.scalalib._"
        case _: PublishModuleConfig => b += "mill.javalib.publish._"
        case _: JavaModuleConfig | CoursierModuleConfig => b += "mill.javalib._"
        case _ =>
    end for
    if (pkg.root.segments.nonEmpty) b += s"$rootModuleAlias._"
    b.result().toSeq.sorted
  }

  protected def writeCrossModuleConfigs(
      ps: PrintStream,
      configs: Seq[ModuleConfig],
      crossConfigs: Seq[(String, Seq[ModuleConfig])]
  ): Unit = {
    configs.foreach:
      case config: CoursierModuleConfig => writeCoursierModuleConfig(ps, config)
      case config: JavaModuleConfig => writeCrossJavaModuleConfig(
          ps,
          config,
          crossConfigs.flatMap: (cross, configs) =>
            configs.collectFirst {
              case config: JavaModuleConfig => config
            }.map((cross, _))
        )
      case config: PublishModuleConfig => writePublishModuleConfig(ps, config)
      case config: ScalaModuleConfig => writeCrossScalaModuleConfig(
          ps,
          config,
          crossConfigs.flatMap: (cross, configs) =>
            configs.collectFirst {
              case config: ScalaModuleConfig => config
            }.map((cross, _))
        )
      case config: ScalaJSModuleConfig => writeScalaJSModuleConfig(ps, config)
      case config: ScalaNativeModuleConfig => writeScalaNativeModuleConfig(ps, config)
  }

  def writeCrossJavaModuleConfig(
      ps: PrintStream,
      config: JavaModuleConfig,
      crossConfigs: Seq[(String, JavaModuleConfig)]
  ): Unit = {
    import config.*
    val crossMandatoryMvnDeps = crossConfigs.collect:
      case (cross, config) if config.mandatoryMvnDeps.nonEmpty => (cross, config.mandatoryMvnDeps)
    if (mandatoryMvnDeps.nonEmpty || crossMandatoryMvnDeps.nonEmpty) {
      ps.println()
      ps.print("def mandatoryMvnDeps = super.mandatoryMvnDeps()")
      if (mandatoryMvnDeps.nonEmpty)
        ps.print(mandatoryMvnDeps.mkString(" ++ Seq(", ", ", ")"))
      if (crossMandatoryMvnDeps.nonEmpty)
        ps.println(" ++ (scalaVersion() match {")
        crossMandatoryMvnDeps.foreach: (cross, deps) =>
          ps.println(deps.mkString(s"case ${literalize(cross)} => Seq(", ", ", ")"))
        ps.println("case _ => Seq()")
        ps.println("})")
    }
    val crossMvnDeps = crossConfigs.collect:
      case (cross, config) if config.mvnDeps.nonEmpty => (cross, config.mvnDeps)
    if (mvnDeps.nonEmpty || crossMvnDeps.nonEmpty) {
      ps.println()
      ps.print("def mvnDeps = super.mvnDeps()")
      if (mvnDeps.nonEmpty)
        ps.print(mvnDeps.mkString(" ++ Seq(", ", ", ")"))
      if (crossMvnDeps.nonEmpty)
        ps.println(" ++ (scalaVersion() match {")
        crossMvnDeps.foreach: (cross, deps) =>
          ps.println(deps.mkString(s"case ${literalize(cross)} => Seq(", ", ", ")"))
        ps.println("case _ => Seq()")
        ps.println("})")
      ps.println()
    }
    val crossCompileMvnDeps = crossConfigs.collect:
      case (cross, config) if config.compileMvnDeps.nonEmpty => (cross, config.compileMvnDeps)
    if (compileMvnDeps.nonEmpty || crossCompileMvnDeps.nonEmpty) {
      ps.println()
      ps.print("def compileMvnDeps = super.compileMvnDeps()")
      if (compileMvnDeps.nonEmpty)
        ps.print(compileMvnDeps.mkString(" ++ Seq(", ", ", ")"))
      if (crossCompileMvnDeps.nonEmpty)
        ps.println(" ++ (scalaVersion() match {")
        crossCompileMvnDeps.foreach: (cross, deps) =>
          ps.println(deps.mkString(s"case ${literalize(cross)} => Seq(", ", ", ")"))
        ps.println("case _ => Seq()")
        ps.println("})")
      ps.println()
    }
    val crossRunMvnDeps = crossConfigs.collect:
      case (cross, config) if config.runMvnDeps.nonEmpty => (cross, config.runMvnDeps)
    if (runMvnDeps.nonEmpty || crossRunMvnDeps.nonEmpty) {
      ps.println()
      ps.print("def runMvnDeps = super.runMvnDeps()")
      if (runMvnDeps.nonEmpty)
        ps.print(runMvnDeps.mkString(" ++ Seq(", ", ", ")"))
      if (crossRunMvnDeps.nonEmpty)
        ps.println(" ++ (scalaVersion() match {")
        crossRunMvnDeps.foreach: (cross, deps) =>
          ps.println(deps.mkString(s"case ${literalize(cross)} => Seq(", ", ", ")"))
        ps.println("case _ => Seq()")
        ps.println("})")
      ps.println()
    }
    if (moduleDeps.nonEmpty)
      ps.println(moduleDeps.map(renderModuleDep)
        .mkString("def moduleDeps = super.moduleDeps ++ Seq(", ", ", ")"))
    if (compileModuleDeps.nonEmpty)
      ps.println(compileModuleDeps.map(renderModuleDep)
        .mkString("def compileModuleDeps = super.compileModuleDeps ++ Seq(", ", ", ")"))
    if (runModuleDeps.nonEmpty)
      ps.println(runModuleDeps.map(renderModuleDep)
        .mkString("def runModuleDeps = super.runModuleDeps ++ Seq(", ", ", ")"))
    if (javacOptions.nonEmpty)
      ps.println(javacOptions.map(literalize(_))
        .mkString("def javacOptions = super.javacOptions() ++ Seq(", ", ", ")"))
  }

  def writeCrossScalaModuleConfig(
      ps: PrintStream,
      config: ScalaModuleConfig,
      crossConfigs: Seq[(String, ScalaModuleConfig)]
  ): Unit = {
    import config.*
    val crossScalacOptions = crossConfigs.collect:
      case (cross, config) if config.scalacOptions.nonEmpty => (cross, config.scalacOptions)
    if (scalacOptions.nonEmpty || crossScalacOptions.nonEmpty)
      ps.println()
      ps.print("def scalacOptions = super.scalacOptions()")
      if (scalacOptions.nonEmpty)
        ps.print(scalacOptions.map(literalize(_)).mkString(" ++ Seq(", ", ", ")"))
      if (crossScalacOptions.nonEmpty)
        ps.println(" ++ (scalaVersion() match {")
        crossScalacOptions.foreach: (cross, options) =>
          ps.println(options.map(literalize(_)).mkString(
            s"case ${literalize(cross)} => Seq(",
            ", ",
            ")"
          ))
        ps.println("case _ => Seq()")
        ps.println("})")
    val crossScalaPluginMvnDeps = crossConfigs.collect:
      case (cross, config) if config.scalacPluginMvnDeps.nonEmpty =>
        (cross, config.scalacPluginMvnDeps)
    if (scalacPluginMvnDeps.nonEmpty || crossScalacOptions.nonEmpty)
      ps.println()
      ps.print("def scalacPluginMvnDeps = super.scalacPluginMvnDeps()")
      if (scalacPluginMvnDeps.nonEmpty)
        ps.print(scalacPluginMvnDeps.mkString(" ++ Seq(", ", ", ")"))
      if (crossScalaPluginMvnDeps.nonEmpty)
        ps.println(" ++ (scalaVersion() match {")
        crossScalaPluginMvnDeps.foreach: (cross, deps) =>
          ps.println(deps.mkString(s"case ${literalize(cross)} => Seq(", ", ", ")"))
        ps.println("case _ => Seq()")
        ps.println("})")
  }
}
