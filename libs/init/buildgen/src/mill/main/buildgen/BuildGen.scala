package mill.main.buildgen

import mill.constants.CodeGenConstants.rootModuleAlias
import mill.constants.OutFiles.millBuild
import mill.init.Util.buildFiles
import mill.main.buildgen.ModuleSpec.*
import mill.util.BuildInfo.millVersion
import pprint.Util.literalize

import java.lang.System.lineSeparator

object BuildGen {

  def withNamedDeps(packages: Seq[PackageSpec]): (Seq[(MvnDep, String)], Seq[PackageSpec]) = {
    val refs = packages.iterator.flatMap(_.module.tree).flatMap { module =>
      (module +: module.test.toSeq).flatMap(module =>
        Seq(
          module.mvnDeps,
          module.compileMvnDeps,
          module.runMvnDeps,
          module.bomMvnDeps,
          module.depManagement,
          module.errorProneDeps,
          module.scalacPluginMvnDeps
        )
      )
    }.flatMap { values =>
      values.base ++ values.cross.flatMap(_._2)
    }.distinct.filter(_.version.nonEmpty).toSeq.groupBy(_.name).flatMap { (name, deps) =>
      val ref = name.split("\\W") match {
        case Array(head) => head
        case parts => parts.tail.map(_.capitalize).mkString(parts.head, "", "")
      }
      deps match {
        case Seq(dep) => Seq((dep, ref))
        case _ => deps.sortBy(_.toString).zipWithIndex.map((dep, i) => (dep, s"`$ref#$i`"))
      }
    }
    val lookup = refs.lift.andThen(_.map(ref => s"Deps.$ref"))

    def updateDeps(values: Values[MvnDep]) = values.copy(
      base = values.base.map(dep => dep.copy(ref = lookup(dep))),
      cross = values.cross.map((k, v) => (k, v.map(dep => dep.copy(ref = lookup(dep)))))
    )
    def updateModule0(module: ModuleSpec) = {
      import module.*
      module.copy(
        mvnDeps = updateDeps(mvnDeps),
        compileMvnDeps = updateDeps(compileMvnDeps),
        runMvnDeps = updateDeps(runMvnDeps),
        bomMvnDeps = updateDeps(bomMvnDeps),
        depManagement = updateDeps(depManagement),
        errorProneDeps = updateDeps(errorProneDeps),
        scalacPluginMvnDeps = updateDeps(scalacPluginMvnDeps)
      )
    }
    def updateModule(module: ModuleSpec): ModuleSpec = updateModule0(module.copy(
      imports = "import millbuild.Deps" +: module.imports,
      test = module.test.map(updateModule0),
      children = module.children.map(updateModule)
    ))
    val packages0 = for (pkg <- packages) yield pkg.copy(module = updateModule(pkg.module))
    (refs.toSeq, packages0)
  }

  def withBaseModule(
      packages: Seq[PackageSpec],
      testSupertype: String,
      moduleHierarchy: String*
  ): Option[(ModuleSpec, Seq[PackageSpec])] = {
    def parentValue[A](a: Value[A], b: Value[A]) = Value(
      if (a.base == b.base) a.base else None,
      a.cross.intersect(b.cross)
    )
    def parentValues[A](a: Values[A], b: Values[A]) = Values(
      a.extend && b.extend,
      a.base.intersect(b.base),
      (a.cross ++ b.cross).groupMapReduce(_._1)(_._2)(_.intersect(_)).toSeq.filter(_._2.nonEmpty)
    )
    def parentModule0(a: ModuleSpec, b: ModuleSpec, defaultSupertypes: Seq[String]) = ModuleSpec(
      name = "",
      imports = (a.imports ++ b.imports).distinct,
      supertypes = a.supertypes.intersect(b.supertypes) match {
        case Nil => defaultSupertypes
        case seq => seq
      },
      mixins = if (a.supertypes == b.supertypes && a.mixins == b.mixins) a.mixins else Nil,
      repositories = parentValues(a.repositories, b.repositories),
      forkArgs = parentValues(a.forkArgs, b.forkArgs),
      forkWorkingDir = parentValue(a.forkWorkingDir, b.forkWorkingDir),
      mvnDeps = parentValues(a.mvnDeps, b.mvnDeps),
      compileMvnDeps = parentValues(a.compileMvnDeps, b.compileMvnDeps),
      runMvnDeps = parentValues(a.runMvnDeps, b.runMvnDeps),
      bomMvnDeps = parentValues(a.bomMvnDeps, b.bomMvnDeps),
      depManagement = parentValues(a.depManagement, b.depManagement),
      javacOptions = parentValues(a.javacOptions, b.javacOptions),
      sourcesFolders = parentValues(a.sourcesFolders, b.sourcesFolders),
      sources = parentValues(a.sources, b.sources),
      resources = parentValues(a.resources, b.resources),
      artifactName = parentValue(a.artifactName, b.artifactName),
      pomPackagingType = parentValue(a.pomPackagingType, b.pomPackagingType),
      pomParentProject = parentValue(a.pomParentProject, b.pomParentProject),
      pomSettings = parentValue(a.pomSettings, b.pomSettings),
      publishVersion = parentValue(a.publishVersion, b.publishVersion),
      versionScheme = parentValue(a.versionScheme, b.versionScheme),
      publishProperties = parentValues(a.publishProperties, b.publishProperties),
      errorProneVersion = parentValue(a.errorProneVersion, b.errorProneVersion),
      errorProneDeps = parentValues(a.errorProneDeps, b.errorProneDeps),
      errorProneOptions = parentValues(a.errorProneOptions, b.errorProneOptions),
      errorProneJavacEnableOptions =
        parentValues(a.errorProneJavacEnableOptions, b.errorProneJavacEnableOptions),
      scalaVersion = parentValue(a.scalaVersion, b.scalaVersion),
      scalacOptions = parentValues(a.scalacOptions, b.scalacOptions),
      scalacPluginMvnDeps = parentValues(a.scalacPluginMvnDeps, b.scalacPluginMvnDeps),
      scalaJSVersion = parentValue(a.scalaJSVersion, b.scalaJSVersion),
      moduleKind = parentValue(a.moduleKind, b.moduleKind),
      scalaNativeVersion = parentValue(a.scalaNativeVersion, b.scalaNativeVersion),
      sourcesRootFolders = parentValues(a.sourcesRootFolders, b.sourcesRootFolders),
      testParallelism = parentValue(a.testParallelism, b.testParallelism),
      testSandboxWorkingDir = parentValue(a.testSandboxWorkingDir, b.testSandboxWorkingDir)
    )
    def parentModule(a: ModuleSpec, b: ModuleSpec) =
      parentModule0(a, b, moduleHierarchy.take(1)).copy(
        test = (a.test.toSeq ++ b.test.toSeq).reduceOption(parentModule0(_, _, Seq(testSupertype)))
      )
    def extendValue[A](a: Value[A], parent: Value[A]) = Value(
      if (a.base == parent.base) None else a.base,
      a.cross.diff(parent.cross)
    )
    def extendValues[A](a: Values[A], parent: Values[A]) = Values(
      a.extend || parent.base.nonEmpty || parent.cross.nonEmpty,
      a.base.diff(parent.base),
      a.cross.map((k, a) =>
        parent.cross.collectFirst {
          case (`k`, b) => (k, a.diff(b))
        }.getOrElse((k, a))
      ).filter(_._2.nonEmpty)
    )
    def extendModule0(a: ModuleSpec, parent: ModuleSpec): ModuleSpec = a.copy(
      supertypes = (parent.name +: a.supertypes).diff(parent.supertypes),
      mixins = if (a.mixins == parent.mixins) Nil else a.mixins,
      repositories = extendValues(a.repositories, parent.repositories),
      forkArgs = extendValues(a.forkArgs, parent.forkArgs),
      forkWorkingDir = extendValue(a.forkWorkingDir, parent.forkWorkingDir),
      mvnDeps = extendValues(a.mvnDeps, parent.mvnDeps),
      compileMvnDeps = extendValues(a.compileMvnDeps, parent.compileMvnDeps),
      runMvnDeps = extendValues(a.runMvnDeps, parent.runMvnDeps),
      bomMvnDeps = extendValues(a.bomMvnDeps, parent.bomMvnDeps),
      depManagement = extendValues(a.depManagement, parent.depManagement),
      javacOptions = extendValues(a.javacOptions, parent.javacOptions),
      sourcesFolders = extendValues(a.sourcesFolders, parent.sourcesFolders),
      sources = extendValues(a.sources, parent.sources),
      resources = extendValues(a.resources, parent.resources),
      artifactName = extendValue(a.artifactName, parent.artifactName),
      pomPackagingType = extendValue(a.pomPackagingType, parent.pomPackagingType),
      pomParentProject = extendValue(a.pomParentProject, parent.pomParentProject),
      pomSettings = extendValue(a.pomSettings, parent.pomSettings),
      publishVersion = extendValue(a.publishVersion, parent.publishVersion),
      versionScheme = extendValue(a.versionScheme, parent.versionScheme),
      publishProperties = extendValues(a.publishProperties, parent.publishProperties),
      errorProneVersion = extendValue(a.errorProneVersion, parent.errorProneVersion),
      errorProneDeps = extendValues(a.errorProneDeps, parent.errorProneDeps),
      errorProneOptions = extendValues(a.errorProneOptions, parent.errorProneOptions),
      errorProneJavacEnableOptions =
        extendValues(a.errorProneJavacEnableOptions, parent.errorProneJavacEnableOptions),
      scalaVersion = extendValue(a.scalaVersion, parent.scalaVersion),
      scalacOptions = extendValues(a.scalacOptions, parent.scalacOptions),
      scalacPluginMvnDeps = extendValues(a.scalacPluginMvnDeps, parent.scalacPluginMvnDeps),
      scalaJSVersion = extendValue(a.scalaJSVersion, parent.scalaJSVersion),
      moduleKind = extendValue(a.moduleKind, parent.moduleKind),
      scalaNativeVersion = extendValue(a.scalaNativeVersion, parent.scalaNativeVersion),
      sourcesRootFolders = extendValues(a.sourcesRootFolders, parent.sourcesRootFolders),
      testParallelism = extendValue(a.testParallelism, parent.testParallelism),
      testSandboxWorkingDir = extendValue(a.testSandboxWorkingDir, parent.testSandboxWorkingDir)
    )
    def isChild(module: ModuleSpec) = module.supertypes.exists(moduleHierarchy.contains)
    def extendModule(a: ModuleSpec, parent: ModuleSpec): ModuleSpec = {
      val a0 = if (isChild(a)) extendModule0(
        a.copy(
          imports = s"import millbuild.${parent.name}" +: a.imports,
          test = a.test.zip(parent.test).map(extendModule0)
        ),
        parent
      )
      else a
      a0.copy(children = a0.children.map(extendModule(_, parent)))
    }

    val childModules = packages.flatMap(_.module.tree).filter(isChild)
    Option.when(childModules.length > 1) {
      val baseModule = childModules.reduce(parentModule)
      val baseModule0 = baseModule.copy(
        name = "ProjectBaseModule",
        imports = baseModule.imports.diff(Seq("import millbuild.Deps")),
        test = baseModule.test.map(_.copy(name = "Tests"))
      )
      val packages0 = packages.map(pkg => pkg.copy(module = extendModule(pkg.module, baseModule0)))
      (baseModule0, packages0)
    }
  }

  def writeBuildFiles(
      packages: Seq[PackageSpec],
      merge: Boolean = false,
      depRefs: Seq[(MvnDep, String)] = Nil,
      baseModule: Option[ModuleSpec] = None,
      millJvmOpts: Seq[String] = Nil
  ): Unit = {
    val fullSpecs = fill(packages)
    val finalSpecs = if (merge) Seq(merged(fullSpecs)) else fullSpecs.sortBy(_.moduleDir)

    val existingBuildFiles = buildFiles(os.pwd)
    if (existingBuildFiles.nonEmpty) {
      println("removing existing build files ...")
      for (file <- existingBuildFiles) do os.remove(file)
    }

    if (depRefs.nonEmpty) {
      val file = os.sub / millBuild / "src/Deps.scala"
      println(s"writing $file")
      os.write(os.pwd / file, renderDepsObject(depRefs), createFolders = true)
    }
    for (spec <- baseModule) do {
      val file = os.sub / millBuild / os.SubPath(s"src/${spec.name}.scala")
      println(s"writing $file")
      os.write(os.pwd / file, renderBaseModule(spec), createFolders = true)
    }
    val root +: nested = finalSpecs: @unchecked
    val millJvmOptsLine = if (millJvmOpts.isEmpty) ""
    else millJvmOpts.mkString("//| mill-jvm-opts: [\"", "\", \"", s"\"]$lineSeparator")
    println("writing build.mill")
    os.write(
      os.pwd / "build.mill",
      s"""//| mill-version: $millVersion
         |//| mill-jvm-version: $millJvmVersion
         |$millJvmOptsLine${renderPackage(root)}
         |""".stripMargin
    )
    for (spec <- nested) do {
      val file = spec.moduleDir / "package.mill"
      println(s"writing $file")
      os.write(os.pwd / file, renderPackage(spec))
    }
  }

  private def fill(packages: Seq[PackageSpec]): Seq[PackageSpec] = {
    def recurse(dir: os.SubPath): Seq[PackageSpec] = {
      val root = packages.find(_.moduleDir == dir).getOrElse(PackageSpec.root(dir))
      val nested = packages.collect {
        case spec if spec.moduleDir.startsWith(dir) && spec.moduleDir != dir =>
          os.sub / spec.moduleDir.segments.take(dir.segments.length + 1)
      }.distinct.flatMap(recurse)
      root +: nested
    }
    recurse(os.sub)
  }

  private def merged(packages: Seq[PackageSpec]): PackageSpec = {
    val root +: nested = packages: @unchecked
    def childPackages(rootDir: os.SubPath) = nested.filter(pkg =>
      pkg.moduleDir.startsWith(rootDir) &&
        pkg.moduleDir.segments.length == rootDir.segments.length + 1
    )
    def toModule(spec: PackageSpec): ModuleSpec = {
      val children = childPackages(spec.moduleDir).map(toModule)
      spec.module.copy(children = spec.module.children ++ children)
    }
    root.copy(module =
      root.module.copy(children =
        root.module.children ++ childPackages(root.moduleDir).map(toModule)
      )
    )
  }

  private def millJvmVersion = {
    val file = os.pwd / ".mill-jvm-version"
    if (os.exists(file)) os.read(file) else "system"
  }

  private val ScalaIdentifier = "^[a-zA-Z_][\\w]*$".r
  private def scalaIdentifier(s: String) = if (ScalaIdentifier.matches(s)) s else s"`$s`"

  private def renderDepsObject(depRefs: Seq[(MvnDep, String)]) = {
    s"""package millbuild
       |import mill.javalib.*
       |object Deps {
       |
       |  ${depRefs.sortBy(_._2).map((d, n) => s"val $n = $d").mkString(lineSeparator)}
       |}""".stripMargin
  }

  private def renderBaseModule(module: ModuleSpec) = {
    import module.*
    s"""package millbuild
       |${renderImports(module)}
       |trait $name ${renderExtendsClause(supertypes ++ mixins)} {
       |
       |  ${renderModuleBody(module)}
       |
       |  ${test.fold("")(renderTestModule("trait", _))}
       |}""".stripMargin
  }

  private def renderImports(module: ModuleSpec) = {
    val imports = module.tree.flatMap(_.imports)
    ("import mill.*" +: imports).distinct.sorted.mkString(lineSeparator)
  }

  private def renderExtendsClause(supertypes: Seq[String]) = {
    if (supertypes.isEmpty) "extends Module"
    else supertypes.mkString("extends ", ", ", "")
  }

  private def renderModuleBody(module: ModuleSpec) = {
    import module.*
    s"""${render("moduleDeps", moduleDeps, encodeModuleDeps, isTask = false)}
       |
       |${render("compileModuleDeps", compileModuleDeps, encodeModuleDeps, isTask = false)}
       |
       |${render("runModuleDeps", runModuleDeps, encodeModuleDeps, isTask = false)}
       |
       |${render("bomModuleDeps", bomModuleDeps, encodeModuleDeps, isTask = false)}
       |
       |${render("mvnDeps", mvnDeps, encodeMvnDeps)}
       |
       |${render("compileMvnDeps", compileMvnDeps, encodeMvnDeps)}
       |
       |${render("runMvnDeps", runMvnDeps, encodeMvnDeps)}
       |
       |${render("bomMvnDeps", bomMvnDeps, encodeMvnDeps)}
       |
       |${render("depManagement", depManagement, encodeMvnDeps)}
       |
       |${render("scalaJSVersion", scalaJSVersion, encodeString)}
       |
       |${render("moduleKind", moduleKind, identity[String])}
       |
       |${render("scalaNativeVersion", scalaNativeVersion, encodeString)}
       |
       |${render("scalaVersion", scalaVersion, encodeString)}
       |
       |${render("scalacOptions", scalacOptions, encodeScalacOptions)}
       |
       |${render("scalacPluginMvnDeps", scalacPluginMvnDeps, encodeMvnDeps)}
       |
       |${render("javacOptions", javacOptions, encodeOpts)}
       |
       |${render("sourcesRootFolders", sourcesRootFolders, encodeStrings, isTask = false)}
       |
       |${render("sourcesFolders", sourcesFolders, encodeStrings, isTask = false)}
       |
       |${renderSources("sources", sources)}
       |
       |${renderSources("resources", resources)}
       |
       |${render("forkArgs", forkArgs, encodeOpts)}
       |
       |${render("forkWorkingDir", forkWorkingDir, encodeRelPath("moduleDir", _))}
       |
       |${render("errorProneVersion", errorProneVersion, encodeString)}
       |
       |${render("errorProneDeps", errorProneDeps, encodeMvnDeps)}
       |
       |${render("errorProneOptions", errorProneOptions, encodeStrings)}
       |
       |${render("errorProneJavacEnableOptions", errorProneJavacEnableOptions, encodeOpts)}
       |
       |${render("artifactName", artifactName, encodeString)}
       |
       |${render("pomPackagingType", pomPackagingType, encodeString)}
       |
       |${render("pomParentProject", pomParentProject, a => s"Some(${encodeArtifact(a)})")}
       |
       |${render("pomSettings", pomSettings, encodePomSettings)}
       |
       |${render("publishVersion", publishVersion, encodeString)}
       |
       |${render("versionScheme", versionScheme, a => s"Some($a)")}
       |
       |${render("publishProperties", publishProperties, encodeProperties)}
       |
       |${render("testParallelism", testParallelism, _.toString)}
       |
       |${render("testSandboxWorkingDir", testSandboxWorkingDir, _.toString)}
       |
       |${render("repositories", repositories, encodeStrings)}
       |""".stripMargin
  }

  private def renderTestModule(scalaType: String, spec: ModuleSpec) = {
    import spec.*
    s"""$scalaType $name ${renderExtendsClause(supertypes ++ mixins)} {
       |
       |  ${renderModuleBody(spec)}
       |}""".stripMargin
  }

  private def renderPackage(spec: PackageSpec) = {
    import spec.*
    val namespace = (rootModuleAlias +: moduleDir.segments.map(scalaIdentifier)).mkString(".")
    s"""package $namespace
       |${renderImports(module)}
       |${renderModule(module, isPackageRoot = true)}
       |""".stripMargin
  }

  private def renderModule(spec: ModuleSpec, isPackageRoot: Boolean = false): String = {
    import spec.*
    val name0 = if (isPackageRoot) "`package`" else scalaIdentifier(name)
    val extendsClause = renderExtendsClause(supertypes ++ mixins)
    val typeDeclaration = if (crossKeys.isEmpty) s"object $name0 $extendsClause"
    else {
      val crossTraitName = scalaIdentifier(name.split("\\W") match {
        case Array("") => s"`${name}Module`"
        case parts => parts.map(_.capitalize).mkString("", "", "Module")
      })
      val crossExtendsClause =
        crossKeys.sorted.mkString(s"extends Cross[$crossTraitName](\"", "\", \"", "\")")
      s"""object $name0 $crossExtendsClause
         |trait $crossTraitName $extendsClause""".stripMargin
    }
    val aliasPart = { if (children.exists(_.useParentModuleDir)) " outer => " else "" }
    val renderModuleDir = if (useParentModuleDir) "def moduleDir = outer.moduleDir" else ""

    s"""$typeDeclaration {$aliasPart
       |
       |  $renderModuleDir
       |
       |  ${renderModuleBody(spec)}
       |
       |  ${test.fold("")(renderTestModule("object", _))}
       |
       |  ${children.sortBy(_.name).map(renderModule(_)).mkString(lineSeparator * 2)}
       |}""".stripMargin
  }

  private def render[A](name: String, value: Value[A], encode: A => String): String = {
    import value.*
    if (cross.isEmpty) base.fold("")(a => s"def $name = ${encode(a)}")
    else encodeCrossMatch(s"def $name = ", cross, base, encode, "")
  }
  private def render[A](
      name: String,
      values: Values[A],
      encode: Seq[A] => String,
      isTask: Boolean = true
  ): String = {
    import values.*
    if (base.isEmpty && cross.isEmpty) ""
    else {
      var stmt = s"def $name = "
      if (extend) {
        stmt += s"super.$name"
        if (isTask) stmt += "()"
      }
      if (base.nonEmpty) {
        if (extend) stmt += " ++ "
        stmt += encode(base)
      }
      if (cross.isEmpty) stmt
      else {
        val stmtEnd = if (extend || base.nonEmpty) {
          stmt += " ++ ("
          ")"
        } else ""
        encodeCrossMatch(stmt, cross, Some(Nil), encode, stmtEnd)
      }
    }
  }
  private def renderSources(name: String, values: Values[os.RelPath]) = {
    def encodeSeq(rels: Seq[os.RelPath]) = rels.map(encodeRelPath("os.rel", _))
      .mkString("Seq(", ", ", ")")
    def encode(rels: Seq[os.RelPath]) = rels.map(rel =>
      if (rel.ups == 0)
        if (rel.segments.isEmpty) "os.sub" else rel.segments.mkString("\"", "/", "\"")
      else encodeRelPath("os.rel", rel)
    ).mkString("Task.Sources(", ", ", ")")
    import values.*
    if (base.isEmpty && cross.isEmpty) ""
    else if (extend) {
      var stmt = s"def $name = super.$name()"
      if (base.nonEmpty) {
        val task = s"custom${name.capitalize}"
        stmt =
          s"""def $task = ${encode(base)}
             |$stmt ++ $task()""".stripMargin
      }
      if (cross.nonEmpty) {
        val task = s"customCross${name.capitalize}"
        stmt =
          s"""def $task = ${encodeCrossMatch("Task.Sources((", cross, Some(Nil), encodeSeq, ")*)")}
             |$stmt ++ $task()""".stripMargin
      }
      stmt
    } else if (cross.isEmpty) s"def $name = ${encode(base)}"
    else {
      var stmt = s"def $name = Task.Sources(("
      val stmtEnd = if (base.isEmpty) ")*)"
      else {
        stmt += encodeSeq(base)
        stmt += " ++ ("
        "))*)"
      }
      encodeCrossMatch(stmt, cross, Some(Nil), encodeSeq, stmtEnd)
    }
  }

  private def encodeCrossMatch[A](
      stmtStart: String,
      crossValues: Seq[(String, A)],
      defaultValue: Option[A],
      encode: A => String,
      stmtEnd: String
  ) = {
    val defaultCase = defaultValue.fold("")(a => s"case _ => ${encode(a)}")
    crossValues.groupMap(_._2)(_._1).map { (a, ks) =>
      val pattern = ks.sorted.mkString("\"", "\" | \"", "\"")
      s"case $pattern => ${encode(a)}"
    }.toSeq.sorted.mkString(
      s"""${stmtStart}crossScalaVersion match {
         |  """.stripMargin,
      """
        |  """.stripMargin,
      s"""
         |  $defaultCase
         |}$stmtEnd""".stripMargin
    )
  }

  private def encodeString[A](a: A) = s"\"$a\""
  private def encodeRelPath(root: String, a: os.RelPath) = {
    val ups = " / os.up" * a.ups
    val segments = if (a.segments.isEmpty) "" else a.segments.mkString(" / \"", "/", "\"")
    s"$root$ups$segments"
  }
  private def encodeMvnDep(a: MvnDep) = a.ref.getOrElse(a.toString)
  private def encodeModuleDep(a: ModuleDep) = {
    import a.*
    val segments = rootModuleAlias +: moduleDir.segments.map(scalaIdentifier)
    val suffix = crossSuffix.getOrElse("") + nestedModule.fold("")("." + _)
    segments.mkString("", ".", suffix)
  }
  private def encodeArtifact(a: Artifact) = {
    import a.*
    s"""Artifact("$group", "$id", "$version")"""
  }
  private def encodePomSettings(a: PomSettings) = {
    def encodeOpt(o: Option[String]) = o.fold("None")(s => s"Some(\"$s\")")
    def encodeLicense(a: License) = {
      import a.*
      s"""License("$id", "$name", "$url", $isOsiApproved, $isFsfLibre, "$distribution")"""
    }
    def encodeVersionControl(a: VersionControl) = {
      import a.*
      val browsableRepository0 = encodeOpt(browsableRepository)
      val connection0 = encodeOpt(connection)
      val devloperConnection0 = encodeOpt(developerConnection)
      val tag0 = encodeOpt(tag)
      s"VersionControl($browsableRepository0, $connection0, $devloperConnection0, $tag0)"
    }
    def encodeDeveloper(a: Developer) = {
      import a.*
      val organization0 = encodeOpt(organization)
      val organizationUrl0 = encodeOpt(organizationUrl)
      s"""Developer("$id", "$name", "$url", $organization0, $organizationUrl0)"""
    }
    import a.*
    val description0 = literalize(description)
    val licenses0 = licenses.map(encodeLicense).mkString("Seq(", ",", ")")
    val versionControl0 = encodeVersionControl(versionControl)
    val developers0 = developers.map(encodeDeveloper).mkString("Seq(", ",", ")")
    s"""PomSettings($description0, "$organization", "$url", $licenses0, $versionControl0, $developers0)"""
  }

  private def encodeStrings[A](as: Seq[A]) = as.mkString("Seq(\"", "\", \"", "\")")
  private def encodeMvnDeps(as: Seq[MvnDep]) =
    as.iterator.map(encodeMvnDep).mkString("Seq(", ", ", ")")
  private def encodeModuleDeps(as: Seq[ModuleDep]) =
    as.iterator.map(encodeModuleDep).mkString("Seq(", ", ", ")")
  private def encodeOpts(as: Seq[Opt]) =
    as.iterator.flatMap(_.group).mkString("Seq(\"", "\", \"", "\")")
  private def encodeScalacOptions(as: Seq[Opt]) =
    as.iterator.flatMap(_.group).map(literalize(_)).mkString("Seq(", ", ", ")")
  private def encodeProperties(as: Seq[(String, String)]) =
    as.iterator.map((k, v) => s"(\"$k\", ${literalize(v)})").mkString("Map(", ", ", ")")
}
