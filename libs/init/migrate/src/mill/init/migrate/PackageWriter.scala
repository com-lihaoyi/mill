package mill.init.migrate

import mill.constants.CodeGenConstants.rootModuleAlias
import mill.internal.Util.backtickWrap
import pprint.Util.literalize

import java.io.PrintStream
import scala.collection.immutable.SortedSet

trait PackageWriter {

  def writePackage(ps: PrintStream, pkg: PackageRepr): Unit = {
    import pkg.*
    ps.print("package ")
    ps.print(rootModuleAlias)
    for segment <- segments do
      ps.print('.')
      ps.print(backtickWrap(segment))
    ps.println()
    for s <- computeImports(pkg) do
      ps.print("import ")
      ps.println(s)
    ps.println()
    writeModuleTree(ps, segments, modules)
  }

  protected def computeImports(pkg: PackageRepr): SortedSet[String]

  def writeModuleTree(ps: PrintStream, segments: Seq[String], modules: Tree[ModuleRepr]): Unit = {
    import modules.*
    writeModule(
      ps,
      segments,
      root,
      () =>
        for child <- modules.children do
          writeModuleTree(ps, segments :+ child.root.main.name, child)
    )
  }

  def writeModule(
      ps: PrintStream,
      segments: Seq[String],
      module: ModuleRepr,
      embed: () => Unit
  ): Unit = {
    import module.*
    writeBaseModule(
      ps,
      main,
      () =>
        for testModule <- test do writeBaseModule(ps, testModule)
        embed()
    )
  }

  def writeBaseModule(
      ps: PrintStream,
      baseModule: ModuleTypedef,
      embed: () => Unit = () => ()
  ): Unit = {
    import baseModule.*
    ps.println()
    ps.print("object ")
    ps.println(backtickWrap(name))
    writeExtendsClause(ps, supertypes)
    ps.println(" {")
    writeModuleConfigs(ps, configs)
    embed()
    ps.println('}')
  }

  def writeExtendsClause(ps: PrintStream, supertypes: Seq[String]) = {
    if (supertypes.nonEmpty)
      ps.print(" extends ")
      ps.print(supertypes.head)
      for supertype <- supertypes.tail do
        ps.print(" with ")
        ps.print(supertype)
  }

  def writeModuleConfigs(ps: PrintStream, configs: Seq[ModuleConfig]) = {
    for config <- configs do
      config match
        case config: CoursierModuleConfig =>
          import config.*
          if (repositories.nonEmpty)
            ps.println()
            ps.print("def repositories = super.repositories() ++ ")
            ps.print("Seq(")
            ps.print(literalize(repositories.head))
            for repository <- repositories.tail do
              ps.print(", ")
              ps.print(literalize(repository))
            ps.println(')')
        case config: JavaModuleConfig =>
          import config.*
          if (mvnDeps.nonEmpty)
            ps.println()
            ps.print("def mvnDeps = super.mvnDeps() ++ ")
            ps.print("Seq(")
            writeDep(ps, mvnDeps.head)
            for dep <- mvnDeps.tail do
              ps.print(", ")
              writeDep(ps, dep)
            ps.println(')')
          if (compileMvnDeps.nonEmpty)
            ps.println()
            ps.print("def compileMvnDeps = super.compileMvnDeps() ++ ")
            ps.print("Seq(")
            writeDep(ps, compileMvnDeps.head)
            for dep <- compileMvnDeps.tail do
              ps.print(", ")
              writeDep(ps, dep)
            ps.println(')')
          if (runMvnDeps.nonEmpty)
            ps.println()
            ps.print("def runMvnDeps = super.runMvnDeps() ++ ")
            ps.print("Seq(")
            writeDep(ps, runMvnDeps.head)
            for dep <- runMvnDeps.tail do
              ps.print(", ")
              writeDep(ps, dep)
            ps.println(')')
          if (moduleDeps.nonEmpty)
            ps.println()
            ps.print("def moduleDeps = super.moduleDeps ++ ")
            ps.print("Seq(")
            writeModuleDep(ps, moduleDeps.head)
            for dep <- moduleDeps.tail do
              ps.print(", ")
              writeModuleDep(ps, dep)
            ps.println(')')
          if (compileModuleDeps.nonEmpty)
            ps.println()
            ps.print("def compileModuleDeps = super.compileModuleDeps ++ ")
            ps.print("Seq(")
            writeModuleDep(ps, compileModuleDeps.head)
            for dep <- compileModuleDeps.tail do
              ps.print(", ")
              writeModuleDep(ps, dep)
            ps.println(')')
          if (runModuleDeps.nonEmpty)
            ps.println()
            ps.print("def runModuleDeps = super.runModuleDeps ++ ")
            ps.print("Seq(")
            writeModuleDep(ps, runModuleDeps.head)
            for dep <- runModuleDeps.tail do
              ps.print(", ")
              writeModuleDep(ps, dep)
            ps.println(')')
          if (javacOptions.nonEmpty)
            ps.println()
            ps.print("def javacOptions = super.javacOptions() ++ ")
            ps.print("Seq(")
            ps.print(literalize(javacOptions.head))
            for option <- javacOptions.tail do
              ps.print(", ")
              ps.print(literalize(option))
            ps.println(')')
        case config: PublishModuleConfig =>
          import config.*
          writePomSettings(ps, pomSettings)
          ps.println()
          ps.print("def publishVersion = ")
          ps.print(literalize(publishVersion))
          writeVersionScheme(ps, versionScheme)
        case config: ScalaModuleConfig =>
          import config.*
          if (scalaVersion.nonEmpty)
            ps.println()
            ps.print("def scalaVersion = ")
            ps.println(literalize(scalaVersion))
          if (scalacOptions.nonEmpty)
            ps.println()
            ps.print("def scalacOptions = super.scalacOptions() ++ ")
            ps.print("Seq(")
            ps.print(literalize(scalacOptions.head))
            for scalacOption <- scalacOptions.tail do
              ps.print(", ")
              ps.print(literalize(scalacOption))
            ps.println(')')
          if (scalacPluginMvnDeps.nonEmpty)
            ps.println()
            ps.print("def scalacPluginMvnDeps = super.scalacPluginMvnDeps() ++ ")
            ps.print("Seq(")
            writeDep(ps, scalacPluginMvnDeps.head)
            for dep <- scalacPluginMvnDeps.tail do
              ps.print(", ")
              writeDep(ps, dep)
            ps.println(")")
        case config: ScalaJSModuleConfig =>
          import config.*
          if (scalaJSVersion.nonEmpty)
            ps.println()
            ps.print("def scalaJSVersion = ")
            ps.println(literalize(scalaJSVersion))
        case config: ScalaNativeModuleConfig =>
          import config.*
          if (scalaNativeVersion.nonEmpty)
            ps.println()
            ps.print("def scalaNativeVersion = ")
            ps.println(literalize(scalaNativeVersion))
  }

  def writeDep(ps: PrintStream, dep: String): Unit = {
    ps.print("mvn")
    ps.print('"')
    ps.print(dep)
    ps.print('"')
  }

  def writeModuleDep(ps: PrintStream, dep: JavaModuleConfig.ModuleDep): Unit = {
    import dep.*
    ps.print(rootModuleAlias)
    if (segments.isEmpty) ps.print(crossArgs.getOrElse(-1, ""))
    else
      for i <- segments.indices do
        ps.print('.')
        ps.print(backtickWrap(segments(i)))
        ps.print(crossArgs.getOrElse(i, ""))
  }

  def writePomSettings(ps: PrintStream, pomSettings: PublishModuleConfig.PomSettings) = {
    import pomSettings.*
    ps.println()
    ps.print("def pomSettings = PomSettings(")
    ps.print(literalize(description))
    ps.println(',')
    ps.print(literalize(organization))
    ps.println(',')
    ps.print(literalize(url))
    ps.println(',')
    ps.print("Seq(")
    if (licenses.nonEmpty)
      writeLicense(ps, licenses.head)
      for license <- licenses.tail do
        ps.println(',')
        writeLicense(ps, license)
    ps.print(')')
    ps.println(',')
    writeVersionControl(ps, versionControl)
    ps.println(',')
    ps.print("Seq(")
    if (developers.nonEmpty)
      writeDeveloper(ps, developers.head)
      for developer <- developers.tail do
        ps.println(',')
        writeDeveloper(ps, developer)
    ps.print(')')
    ps.println(')')
  }

  def writeLicense(ps: PrintStream, license: PublishModuleConfig.License) = {
    import license.*
    ps.print("License(")
    ps.print(literalize(id))
    ps.print(", ")
    ps.print(literalize(name))
    ps.print(", ")
    ps.print(literalize(url))
    ps.print(", ")
    ps.print(isOsiApproved)
    ps.print(", ")
    ps.print(isFsfLibre)
    ps.print(", ")
    ps.print(literalize(distribution))
    ps.print(')')
  }

  def writeVersionControl(ps: PrintStream, versionControl: PublishModuleConfig.VersionControl) = {
    import versionControl.*
    ps.print("VersionControl(")
    var wroteArg0 = false
    for value <- browsableRepository do
      ps.print("browsableRepository = Some(")
      ps.print(literalize(value))
      ps.print(')')
      wroteArg0 = true
    for value <- connection do
      if (wroteArg0) ps.println(',')
      ps.print("connection = Some(")
      ps.print(literalize(value))
      ps.print(')')
      wroteArg0 = true
    for value <- developerConnection do
      if (wroteArg0) ps.println(',')
      ps.print("developerConnection = Some(")
      ps.print(literalize(value))
      ps.print(')')
      wroteArg0 = true
    for value <- tag do
      if (wroteArg0) ps.println(',')
      ps.print("tag = Some(")
      ps.print(literalize(value))
      ps.print(')')
    ps.print(')')
  }

  def writeDeveloper(ps: PrintStream, developer: PublishModuleConfig.Developer) = {
    import developer.*
    ps.print("Developer(")
    ps.print(literalize(id))
    ps.print(',')
    ps.print(literalize(name))
    ps.print(',')
    ps.print(literalize(url))
    for value <- organization do
      ps.print(',')
      ps.print("Some(")
      ps.print(literalize(value))
      ps.print(')')
    for value <- organizationUrl do
      ps.print(',')
      ps.print("organizationUrl = Some(")
      ps.print(literalize(value))
      ps.print(')')
    ps.print(')')
  }

  def writeVersionScheme(ps: PrintStream, versionScheme: Option[String]) = {
    for scheme <- versionScheme.collect {
        case "early-semver" => "EarlySemVer"
        case "pvp" => "PVP"
        case "semver-spec" => "SemVerSpec"
        case "strict" => "Strict"
      }
    do
      ps.println()
      ps.print("def versionScheme = Some(VersionScheme.")
      ps.print(scheme)
      ps.println(')')
  }
}
