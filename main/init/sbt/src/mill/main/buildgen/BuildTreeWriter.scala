package mill.main.buildgen

import mill.constants.CodeGenConstants.{nestedBuildFileNames, rootBuildFileNames, rootModuleAlias}
import mill.runner.FileImportGraph
import mill.runner.FileImportGraph.backtickWrap

class BuildTreeWriter(packages: Tree[MetaPackage], workspace: os.Path = os.pwd):

  def write() =
    packages.nodes.foreach: pkg =>
      val fileName =
        if os.sub == pkg.baseDir then rootBuildFileNames.get(0) else nestedBuildFileNames.get(0)
      val subFile = pkg.baseDir / fileName
      val contents = renderPackage(pkg)
      println(s"writing build file $subFile")
      os.write(workspace / subFile, contents)

  // TODO mark private?
  def renderAlias(baseDir: os.SubPath) =
    if baseDir == os.sub then rootModuleAlias
    else baseDir.segments0.iterator.map(backtickWrap).mkString(s"$rootModuleAlias.", ".", "")

  def isCrossModule(baseDir: os.SubPath) =
    def isCrossDepIn(baseDir: os.SubPath, module: MetaModule): Boolean =
      if module.crossVersions.isEmpty then false
      else
        val newBase = baseDir / module.name
        baseDir == newBase || module.nestedModules.exists(isCrossDepIn(newBase, _))

    packages.nodes(using Tree.Traversal.BreadthFirst)
      .filter: pkg =>
        baseDir.startsWith(pkg.baseDir)
      .exists: pkg =>
        baseDir == pkg.baseDir ||
          baseDir.startsWith(pkg.baseDir) && isCrossDepIn(pkg.baseDir, pkg.module)
  end isCrossModule

  def renderPackage(meta: MetaPackage) =
    val linebreak =
      s"""
         |""".stripMargin
    import meta.*
    s"""package ${renderAlias(baseDir)}
       |${imports.mkString(linebreak)}
       |${renderModule(module, isPackage = true)}
       |""".stripMargin

  def renderModule(meta: MetaModule, isPackage: Boolean = false): String =
    val linebreak =
      s"""
         |""".stripMargin
    import meta.*
    val objName = if isPackage then "`package`" else backtickWrap(name)
    if crossVersions.isEmpty then
      val objSupertypes = if isPackage then "RootModule" +: supertypes else supertypes
      s"""object $objName ${renderExtends(objSupertypes)} {
         |${renderModuleConfigs(moduleConfigs)}
         |${nestedModules.iterator.map(renderModule(_)).mkString(linebreak)}
         |}""".stripMargin
    else
      val crossModuleName = backtickWrap(s"${name.capitalize}Module")
      val crossVarargs = crossVersions.mkString("\"", "\",\"", "\"")
      val objExtends =
        (if isPackage then "RootModule with " else "") +
          s"Cross[$crossModuleName]($crossVarargs)"
      s"""object $objName extends $objExtends
         |trait $crossModuleName ${renderExtends(supertypes)} {
         |${renderModuleConfigs(moduleConfigs)}
         |${nestedModules.iterator.map(renderModule(_)).mkString(linebreak)}
         |}
         |""".stripMargin

  def renderExtends(supertypes: Seq[String]) = supertypes match
    case Seq() => ""
    case Seq(head) => s"extends $head"
    case head +: tail => tail.mkString(s"extends $head with ", " with ", "")

  def renderModuleConfigs(irs: IterableOnce[IrModuleConfig]) =
    irs.iterator
      .map:
        case ir: IrCoursierModuleConfig => renderCoursierModuleConfig(ir)
        case ir: IrJavaModuleConfig => renderJavaModuleConfig(ir)
        case ir: IrPublishModuleConfig => renderPublishModuleConfig(ir)
        case ir: IrScalaModuleConfig => renderScalaModuleConfig(ir)
        case ir: IrScalaJSModuleConfig => renderScalaJSModuleConfig(ir)
        case ir: IrScalaNativeModuleConfig => renderScalaNativeModuleConfig(ir)
      .mkString(s"""
                   |""".stripMargin)

  def renderCoursierModuleConfig(ir: IrCoursierModuleConfig) =
    import ir.*
    s"""${renderRepositories(repositories)}
       |""".stripMargin

  def renderJavaModuleConfig(ir: IrJavaModuleConfig) =
    import ir.*
    s"""${renderJavacOptions(javacOptions)}
       |${renderSources(sources)}
       |${renderResources(resources)}
       |${renderIvyDeps(ivyDeps)}
       |${renderModuleDeps(moduleDeps)}
       |${renderCompileIvyDeps(compileIvyDeps)}
       |${renderCompileModuleDeps(compileModuleDeps)}
       |${renderRunIvyDeps(runIvyDeps)}
       |${renderRunModuleDeps(runModuleDeps)}
       |""".stripMargin

  def renderPublishModuleConfig(ir: IrPublishModuleConfig) =
    import ir.*
    s"""${renderPomSettings(pomSettings)}
       |${renderPublishVersion(publishVersion)}
       |""".stripMargin

  def renderScalaModuleConfig(ir: IrScalaModuleConfig) =
    import ir.*
    s"""${renderScalaVersion(scalaVersion)}
       |${renderScalacOptions(scalacOptions)}
       |""".stripMargin

  def renderScalaJSModuleConfig(ir: IrScalaJSModuleConfig) =
    import ir.*
    s"""${renderScalaJSVersion(scalaJSVersion)}
       |""".stripMargin

  def renderScalaNativeModuleConfig(ir: IrScalaNativeModuleConfig) =
    import ir.*
    s"""${renderScalaNativeVersion(scalaNativeVersion)}
       |""".stripMargin

  def renderRepositories(strs: Seq[String]) =
    if strs.isEmpty then ""
    else
      strs.mkString(
        "def repositoriesTask = Task.Anon(super.repositoriesTask() ++ Seq(MavenRepository(\"",
        "\"),MavenRepository(\"",
        "\")))"
      )

  def renderJavacOptions(strs: Seq[String]) =
    if strs.isEmpty then ""
    else strs.mkString("def javacOptions = super.javacOptions() ++ Seq(\"", "\",\"", "\")")

  def renderSources(rels: Seq[os.RelPath]) =
    if rels.isEmpty then ""
    else
      rels
        .mkString(
          "def sources = Task.Sources(\"",
          "\",\"",
          "\")"
        )

  def renderResources(rels: Seq[os.RelPath]) =
    if rels.isEmpty then ""
    else
      rels
        .mkString(
          "def resources = Task.Sources(\"",
          "\",\"",
          "\")"
        )

  def renderIvyDeps(deps: Seq[IrDep]) =
    if deps.isEmpty then ""
    else
      deps.iterator
        .map(renderDep)
        .mkString(s"def ivyDeps = super.ivyDeps() ++ Agg(", ",", ")")

  def renderModuleDeps(subs: Seq[os.SubPath]) =
    if subs.isEmpty then ""
    else
      subs.iterator
        .map(renderModuleDep)
        .mkString(s"def moduleDeps = super.moduleDeps ++ Seq(", ",", ")")

  def renderCompileIvyDeps(deps: Seq[IrDep]) =
    if deps.isEmpty then ""
    else
      deps.iterator
        .map(renderDep)
        .mkString(s"def compileIvyDeps = super.compileIvyDeps() ++ Agg(", ",", ")")

  def renderCompileModuleDeps(subs: Seq[os.SubPath]) =
    if subs.isEmpty then ""
    else
      subs.iterator
        .map(renderModuleDep)
        .mkString(s"def compileModuleDeps = super.compileModuleDeps() ++ Seq(", ",", ")")

  def renderRunIvyDeps(deps: Seq[IrDep]) =
    if deps.isEmpty then ""
    else
      deps.iterator
        .map(renderDep)
        .mkString(s"def runIvyDeps = super.runIvyDeps() ++ Agg(", ",", ")")

  def renderRunModuleDeps(subs: Seq[os.SubPath]) =
    if subs.isEmpty then ""
    else
      subs.iterator
        .map(renderModuleDep)
        .mkString(s"def runModuleDeps = super.runModuleDeps() ++ Seq(", ",", ")")

  def renderDep(ir: IrDep) =
    import ir.*
    val sepType = `type` match
      case None | Some("jar") => ""
      case value => s";type=$value"
    val sepClassfier = classifier.fold("")(";classifier=" + _)
    val attrs = sepType + sepClassfier
    import IrCrossVersion.*
    val encoded = ir.crossVersion match
      case Constant("", false) => s"$organization:$name:$version;$attrs"
      case Constant("", true) => s"$organization:$name::$version;$attrs"
      case Binary(false) => s"$organization::$name:$version;$attrs"
      case Binary(true) => s"$organization::$name::$version;$attrs"
      case Full(false) => s"$organization:::$name:$version;$attrs"
      case Full(true) => s"$organization:::$name::$version;$attrs"
      case _: Constant => s"$organization:$name::$version;$attrs"
    s"ivy\"$encoded\""

  def renderModuleDep(baseDir: os.SubPath) =
    val alias = renderAlias(baseDir)
    if isCrossModule(baseDir) then alias + "()" else alias

  def renderPomSettings(ir: IrPomSettings) =
    def mkOpt(opt: Option[String]) = opt.fold("None")(s => s"Some(\"$s\")")

    def mkSeq(it: IterableOnce[String]) = it.iterator.mkString("Seq(", ",", ")")

    def renderLicense(ir: IrLicense) =
      import ir.*
      s"License(\"$id\",\"$name\",\"$url\",$isOsiApproved,$isFsfLibre,\"$distribution\")"

    def renderVersionControl(ir: IrVersionControl) =
      import ir.*
      s"VersionControl(${mkOpt(url)},${mkOpt(connection)},${mkOpt(developerConnection)},${mkOpt(tag)})"

    def renderDeveloper(ir: IrDeveloper) =
      import ir.*
      s"Developer(\"$id\",\"$name\",\"$url\",${mkOpt(organization)},${mkOpt(organizationUrl)})"

    import ir.*
    val lics = mkSeq(licenses.iterator.map(renderLicense))
    val verc = renderVersionControl(versionControl)
    val devs = mkSeq(developers.iterator.map(renderDeveloper))
    s"def pomSettings = PomSettings(\"$description\",\"$organization\",\"$url\",$lics,$verc,$devs)"

  def renderPublishVersion(str: String) =
    s"def publishVersion = \"$str\""

  def renderScalaVersion(str: String) =
    s"def scalaVersion = \"$str\""

  def renderScalacOptions(strs: Seq[String]) =
    if strs.isEmpty then ""
    else strs.mkString(s"def scalacOptions = super.scalacOptions() ++ Seq(\"", "\",\"", "\")")

  def renderScalaJSVersion(str: String) =
    s"def scalaJSVersion = \"$str\""

  def renderScalaNativeVersion(str: String) =
    s"def scalaNativeVersion = \"$str\""

end BuildTreeWriter
