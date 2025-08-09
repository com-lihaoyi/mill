package mill.main.sbt

import mill.constants.CodeGenConstants.rootModuleAlias
import mill.main.buildgen.*
import pprint.Util.literalize

import java.io.PrintStream

object SbtBuildWriter extends BuildWriter {

  def writeImports(ps: PrintStream, pkg: Tree[ModuleRepr]) = {
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

    ps.println()
    b.result().toSeq.sorted.foreach: s =>
      ps.println(s"import $s")
  }

  protected def writeCrossModuleConfigs(
      ps: PrintStream,
      configs: Seq[ModuleConfig],
      crossConfigs: Seq[(String, Seq[ModuleConfig])]
  ): Unit = {
    // cross config export is limited to JavaModuleConfig and ScalaModuleConfig
    configs.foreach:
      case config: CoursierModuleConfig => writeCoursierModuleConfig(ps, config)
      case config: JavaModuleConfig => writeCrossJavaModuleConfig(
          ps,
          config,
          crossConfigs.flatMap: (cross, configs) =>
            configs.collectFirst {
              case config: JavaModuleConfig => (cross, config)
            }
        )
      case config: PublishModuleConfig => writePublishModuleConfig(ps, config)
      case config: ScalaModuleConfig => writeCrossScalaModuleConfig(
          ps,
          config,
          crossConfigs.flatMap: (cross, configs) =>
            configs.collectFirst {
              case config: ScalaModuleConfig => (cross, config)
            }
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
        crossMandatoryMvnDeps.groupMap(_._2)(_._1).foreach: (deps, crosses) =>
          ps.println(deps
            .mkString(s"case ${crosses.map(literalize(_)).mkString(" | ")} => Seq(", ", ", ")"))
        ps.println("case _ => Seq()")
        ps.print("})")
      ps.println()
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
        crossMvnDeps.groupMap(_._2)(_._1).foreach: (deps, crosses) =>
          ps.println(deps
            .mkString(s"case ${crosses.map(literalize(_)).mkString(" | ")} => Seq(", ", ", ")"))
        ps.println("case _ => Seq()")
        ps.print("})")
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
        crossCompileMvnDeps.groupMap(_._2)(_._1).foreach: (deps, crosses) =>
          ps.println(deps
            .mkString(s"case ${crosses.map(literalize(_)).mkString(" | ")} => Seq(", ", ", ")"))
        ps.println("case _ => Seq()")
        ps.print("})")
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
        crossRunMvnDeps.groupMap(_._2)(_._1).foreach: (deps, crosses) =>
          ps.println(deps
            .mkString(s"case ${crosses.map(literalize(_)).mkString(" | ")} => Seq(", ", ", ")"))
        ps.println("case _ => Seq()")
        ps.print("})")
      ps.println()
    }
    if (moduleDeps.nonEmpty)
      ps.println()
      ps.println(moduleDeps.map(renderModuleDep)
        .mkString("def moduleDeps = super.moduleDeps ++ Seq(", ", ", ")"))
    if (compileModuleDeps.nonEmpty)
      ps.println()
      ps.println(compileModuleDeps.map(renderModuleDep)
        .mkString("def compileModuleDeps = super.compileModuleDeps ++ Seq(", ", ", ")"))
    if (runModuleDeps.nonEmpty)
      ps.println()
      ps.println(runModuleDeps.map(renderModuleDep)
        .mkString("def runModuleDeps = super.runModuleDeps ++ Seq(", ", ", ")"))
    val crossJavacOptions = crossConfigs.collect:
      case (cross, config) if config.javacOptions.nonEmpty => (cross, config.javacOptions)
    if (javacOptions.nonEmpty || crossJavacOptions.nonEmpty) {
      ps.println()
      ps.print("def javacOptions = super.javacOptions()")
      if (javacOptions.nonEmpty)
        ps.print(javacOptions.map(literalize(_)).mkString(" ++ Seq(", ", ", ")"))
      if (crossJavacOptions.nonEmpty)
        ps.println(" ++ (scalaVersion() match {")
        crossJavacOptions.groupMap(_._2)(_._1).foreach: (options, crosses) =>
          ps.println(options.map(literalize(_))
            .mkString(s"case ${crosses.map(literalize(_)).mkString(" | ")} => Seq(", ", ", ")"))
        ps.println("case _ => Seq()")
        ps.print("})")
      ps.println()
    }
  }

  def writeCrossScalaModuleConfig(
      ps: PrintStream,
      config: ScalaModuleConfig,
      crossConfigs: Seq[(String, ScalaModuleConfig)]
  ): Unit = {
    import config.*
    val crossScalacOptions = crossConfigs.collect:
      case (cross, config) if config.scalacOptions.nonEmpty => (cross, config.scalacOptions)
    if (scalacOptions.nonEmpty || crossScalacOptions.nonEmpty) {
      ps.println()
      ps.print("def scalacOptions = super.scalacOptions()")
      if (scalacOptions.nonEmpty)
        ps.print(scalacOptions.map(literalize(_)).mkString(" ++ Seq(", ", ", ")"))
      if (crossScalacOptions.nonEmpty)
        ps.println(" ++ (scalaVersion() match {")
        crossScalacOptions.groupMap(_._2)(_._1).foreach: (options, crosses) =>
          ps.println(options.map(literalize(_))
            .mkString(s"case ${crosses.map(literalize(_)).mkString(" | ")} => Seq(", ", ", ")"))
        ps.println("case _ => Seq()")
        ps.print("})")
      ps.println()
    }
    val crossScalaPluginMvnDeps = crossConfigs.collect:
      case (cross, config) if config.scalacPluginMvnDeps.nonEmpty =>
        (cross, config.scalacPluginMvnDeps)
    if (scalacPluginMvnDeps.nonEmpty || crossScalacOptions.nonEmpty) {
      ps.println()
      ps.print("def scalacPluginMvnDeps = super.scalacPluginMvnDeps()")
      if (scalacPluginMvnDeps.nonEmpty)
        ps.print(scalacPluginMvnDeps.mkString(" ++ Seq(", ", ", ")"))
      if (crossScalaPluginMvnDeps.nonEmpty)
        ps.println(" ++ (scalaVersion() match {")
        crossScalaPluginMvnDeps.groupMap(_._2)(_._1).foreach: (deps, crosses) =>
          ps.println(deps
            .mkString(s"case ${crosses.map(literalize(_)).mkString(" | ")} => Seq(", ", ", ")"))
        ps.println("case _ => Seq()")
        ps.print("})")
      ps.println()
    }
  }
}
