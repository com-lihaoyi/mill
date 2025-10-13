package mill.integration

import mill.testkit.IntegrationTester

object IntegrationTesterUtil {

  def renderAllImportedConfigurations(tester: IntegrationTester) = {
    import tester.eval
    val repositories = eval(("show", "__.repositories")).out
    val jvmId = eval(("show", "__.jvmId")).out
    val mvnDeps = eval(("show", "__.mvnDeps")).out
    val compileMvnDeps = eval(("show", "__.compileMvnDeps")).out
    val runMvnDeps = eval(("show", "__.runMvnDeps")).out
    val bomMvnDeps = eval(("show", "__.bomMvnDeps")).out
    val showModuleDeps = eval("__.showModuleDeps").out
    val javacOptions = eval(("show", "__.javacOptions")).out
    val pomParentProject = eval(("show", "__.pomParentProject")).out
    val pomSettings = eval(("show", "__.pomSettings")).out
    val publishVersion = eval(("show", "__.publishVersion")).out
    val versionScheme = eval(("show", "__.versionScheme")).out
    val publishProperties = eval(("show", "__.publishProperties")).out
    val errorProneVersion = eval(("show", "__.errorProneVersion")).out
    val errorProneDeps = eval(("show", "__.errorProneDeps")).out
    val errorProneJavacEnableOptions = eval(("show", "errorProneJavacEnableOptions")).out
    val errorProneOptions = eval(("show", "__.errorProneOptions")).out
    val scalaVersion = eval(("show", "__.scalaVersion")).out
    val scalacOptions = eval(("show", "__.scalacOptions")).out
    val scalacPluginMvnDeps = eval(("show", "__.scalacPluginMvnDeps")).out
    val scalaJSVersion = eval(("show", "__.scalaJSVersion")).out
    val moduleKind = eval(("show", "__.moduleKind")).out
    val scalaNativeVersion = eval(("show", "__.scalaNativeVersion")).out
    s"""$repositories
       |$jvmId
       |$mvnDeps
       |$compileMvnDeps
       |$runMvnDeps
       |$bomMvnDeps
       |$showModuleDeps
       |$javacOptions
       |$pomParentProject
       |$pomSettings
       |$publishVersion
       |$versionScheme
       |$publishProperties
       |$errorProneVersion
       |$errorProneDeps
       |$errorProneJavacEnableOptions
       |$errorProneOptions
       |$scalaVersion
       |$scalacOptions
       |$scalacPluginMvnDeps
       |$scalaJSVersion
       |$moduleKind
       |$scalaNativeVersion
       |""".stripMargin
  }
}
