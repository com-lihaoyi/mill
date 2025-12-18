package mill.main.buildgen

import mill.main.buildgen.ModuleSpec.*
import pprint.Util.literalize

import java.lang.System.lineSeparator

object BuildGen {

  def withNamedDeps(packages: Seq[PackageSpec]): (Seq[(MvnDep, String)], Seq[PackageSpec]) = {
    val names = packages.flatMap(_.module.tree).flatMap { module =>
      (module +: module.test.toSeq).flatMap(module =>
        Seq(
          module.mandatoryMvnDeps,
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
    }.distinct.filter(_.version.nonEmpty).groupBy(_.name).flatMap { (name, deps) =>
      val ref = name.split("\\W") match {
        case Array(head) => head
        case parts => parts.tail.map(_.capitalize).mkString(parts.head, "", "")
      }
      deps match {
        case Seq(dep) => Seq((dep, ref))
        case _ => deps.sortBy(_.toString).zipWithIndex.map((dep, i) => (dep, s"`$ref#$i`"))
      }
    }
    val lookup = names.lift.andThen(_.map(ref => s"Deps.$ref"))

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
    (names.toSeq, packages0)
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
      a.base.intersect(b.base),
      (a.cross ++ b.cross).groupMapReduce(_._1)(_._2)(_.intersect(_)).toSeq.filter(_._2.nonEmpty),
      a.appendSuper && b.appendSuper
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
      mandatoryMvnDeps = parentValues(a.mandatoryMvnDeps, b.mandatoryMvnDeps),
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
    def extendValue[A](a: Value[A], parent: Value[A]) = a.copy(
      if (a.base == parent.base) None else a.base,
      a.cross.diff(parent.cross)
    )
    def extendValues[A](a: Values[A], parent: Values[A]) = a.copy(
      a.base.diff(parent.base),
      a.cross.map((k, a) =>
        parent.cross.collectFirst {
          case (`k`, b) => (k, a.diff(b))
        }.getOrElse((k, a))
      ).filter(_._2.nonEmpty),
      a.appendSuper || parent.base.nonEmpty || parent.cross.nonEmpty
    )
    def extendModule0(a: ModuleSpec, parent: ModuleSpec): ModuleSpec = a.copy(
      supertypes = (parent.name +: a.supertypes).diff(parent.supertypes),
      mixins = if (a.mixins == parent.mixins) Nil else a.mixins,
      repositories = extendValues(a.repositories, parent.repositories),
      forkArgs = extendValues(a.forkArgs, parent.forkArgs),
      forkWorkingDir = extendValue(a.forkWorkingDir, parent.forkWorkingDir),
      mandatoryMvnDeps = extendValues(a.mandatoryMvnDeps, parent.mandatoryMvnDeps),
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

  def buildFiles(workspace: os.Path): Seq[os.Path] = {
    val skip = Seq(workspace / "mill-build", workspace / "out")
    os.walk.stream(workspace, skip = skip.contains).filter(path =>
      os.isFile(path) && (path.last == "build.mill" || path.last == "package.mill")
    ).toSeq ++ {
      val path = workspace / "mill-build"
      if (os.exists(path)) os.walk.stream(path).filter(os.isFile).toSeq else Nil
    }
  }

  def writeBuildFiles(
      packages: Seq[PackageSpec],
      millJvmVersion: String,
      merge: Boolean = false,
      depNames: Seq[(MvnDep, String)] = Nil,
      baseModule: Option[ModuleSpec] = None,
      millJvmOpts: Seq[String] = Nil
  ): Unit = {
    var packages0 = fillPackages(packages).sortBy(_.dir)
    packages0 = if (merge) Seq(mergePackages(packages0.head, packages0.tail)) else packages0
    val existingBuildFiles =
      os.walk(os.pwd, skip = Seq(os.pwd / "mill-build", os.pwd / "out").contains).filter(path =>
        os.isFile(path) && (path.last == "build.mill" || path.last == "package.mill")
      ) ++ (
        if (os.exists(os.pwd / "mill-build")) os.walk(os.pwd / "mill-build").filter(os.isFile)
        else Nil
      )
    if (existingBuildFiles.nonEmpty) {
      println("removing existing build files ...")
      for (file <- existingBuildFiles) do os.remove(file)
    }

    if (depNames.nonEmpty) {
      val file = os.sub / "mill-build/src/Deps.scala"
      println(s"writing $file")
      os.write(os.pwd / file, renderDepsObject(depNames), createFolders = true)
    }
    for (module <- baseModule) do {
      val file = os.sub / os.SubPath(s"mill-build/src/${module.name}.scala")
      println(s"writing $file")
      os.write(os.pwd / file, renderBaseModule(module), createFolders = true)
    }
    val rootPackage +: nestedPackages = packages0: @unchecked
    val millJvmOptsLine = if (millJvmOpts.isEmpty) ""
    else millJvmOpts.mkString("//| mill-jvm-opts: [\"", "\", \"", s"\"]$lineSeparator")
    println("writing build.mill")
    os.write(
      os.pwd / "build.mill",
      s"""//| mill-version: SNAPSHOT
         |//| mill-jvm-version: $millJvmVersion
         |$millJvmOptsLine${renderPackage(rootPackage)}
         |""".stripMargin
    )
    for (pkg <- nestedPackages) do {
      val file = os.sub / pkg.dir / "package.mill"
      println(s"writing $file")
      os.write(os.pwd / file, renderPackage(pkg))
    }
  }

  private def fillPackages(packages: Seq[PackageSpec]): Seq[PackageSpec] = {
    def recurse(dir: os.SubPath): Seq[PackageSpec] = {
      val root = packages.find(_.dir == dir).getOrElse(PackageSpec.root(dir))
      val nested = packages.collect {
        case pkg if pkg.dir.startsWith(dir) && pkg.dir != dir =>
          os.sub / pkg.dir.segments.take(dir.segments.length + 1)
      }.distinct.flatMap(recurse)
      root +: nested
    }
    recurse(os.sub)
  }

  private def mergePackages(root: PackageSpec, nested: Seq[PackageSpec]): PackageSpec = {
    def newChildren(parentDir: os.SubPath): Seq[ModuleSpec] = {
      val childDepth = parentDir.segments.length + 1
      nested.collect {
        case child if child.dir.startsWith(parentDir) && child.dir.segments.length == childDepth =>
          child.module.copy(children = child.module.children ++ newChildren(child.dir))
      }
    }
    root.copy(module =
      root.module.copy(children = root.module.children ++ newChildren(root.dir))
    )
  }

  private val ScalaIdentifier = "^[a-zA-Z_][\\w]*$".r
  private def toScalaIdentifier(s: String) = if (ScalaIdentifier.matches(s)) s else s"`$s`"

  private def renderDepsObject(depNames: Seq[(MvnDep, String)]) = {
    s"""package millbuild
       |import mill.javalib.*
       |object Deps {
       |
       |  ${depNames.sortBy(_._2).map((d, n) => s"val $n = $d").mkString(lineSeparator)}
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
    s"""${render("moduleDeps", moduleDeps, encodeModuleDep, isTask = false)}
       |
       |${render("compileModuleDeps", compileModuleDeps, encodeModuleDep, isTask = false)}
       |
       |${render("runModuleDeps", runModuleDeps, encodeModuleDep, isTask = false)}
       |
       |${render("bomModuleDeps", bomModuleDeps, encodeModuleDep, isTask = false)}
       |
       |${render("mandatoryMvnDeps", mandatoryMvnDeps, encodeMvnDep)}
       |
       |${render("mvnDeps", mvnDeps, encodeMvnDep)}
       |
       |${render("compileMvnDeps", compileMvnDeps, encodeMvnDep)}
       |
       |${render("runMvnDeps", runMvnDeps, encodeMvnDep)}
       |
       |${render("bomMvnDeps", bomMvnDeps, encodeMvnDep)}
       |
       |${render("depManagement", depManagement, encodeMvnDep)}
       |
       |${render("scalaJSVersion", scalaJSVersion, encodeString)}
       |
       |${render("moduleKind", moduleKind, identity[String])}
       |
       |${render("scalaNativeVersion", scalaNativeVersion, encodeString)}
       |
       |${render("scalaVersion", scalaVersion, encodeString)}
       |
       |${render("scalacOptions", scalacOptions, encodeLiteralOpt)}
       |
       |${render("scalacPluginMvnDeps", scalacPluginMvnDeps, encodeMvnDep)}
       |
       |${render("javacOptions", javacOptions, encodeOpt)}
       |
       |${render("sourcesRootFolders", sourcesRootFolders, encodeSubPath, isTask = false)}
       |
       |${render("sourcesFolders", sourcesFolders, encodeSubPath, isTask = false)}
       |
       |${renderSources("sources", sources)}
       |
       |${renderSources("resources", resources)}
       |
       |${render("forkArgs", forkArgs, encodeOpt)}
       |
       |${render("forkWorkingDir", forkWorkingDir, encodeRelPath("moduleDir", _))}
       |
       |${render("errorProneDeps", errorProneDeps, encodeMvnDep)}
       |
       |${render("errorProneOptions", errorProneOptions, encodeString)}
       |
       |${render("errorProneJavacEnableOptions", errorProneJavacEnableOptions, encodeOpt)}
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
       |${render("publishProperties", publishProperties, encodeProperty, collection = "Map")}
       |
       |${render("testParallelism", testParallelism, _.toString)}
       |
       |${render("testSandboxWorkingDir", testSandboxWorkingDir, _.toString)}
       |
       |${render("repositories", repositories, encodeString)}
       |""".stripMargin
  }

  private def renderTestModule(scalaType: String, spec: ModuleSpec) = {
    import spec.*
    s"""$scalaType $name ${renderExtendsClause(supertypes ++ mixins)} {
       |
       |  ${renderModuleBody(spec)}
       |}""".stripMargin
  }

  private def renderPackage(pkg: PackageSpec) = {
    import pkg.*
    val namespace = ("build" +: dir.segments.map(toScalaIdentifier)).mkString(".")
    s"""package $namespace
       |${renderImports(module)}
       |${renderModule(module, isPackageRoot = true)}
       |""".stripMargin
  }

  private def renderModule(module: ModuleSpec, isPackageRoot: Boolean = false): String = {
    import module.*
    val name0 = if (isPackageRoot) "`package`" else toScalaIdentifier(name)
    val extendsClause = renderExtendsClause(supertypes ++ mixins)
    val typeDeclaration = if (crossKeys.isEmpty) s"object $name0 $extendsClause"
    else {
      val crossTraitName = toScalaIdentifier(name.split("\\W") match {
        case Array("") => s"`${name}Module`"
        case parts => parts.map(_.capitalize).mkString("", "", "Module")
      })
      val crossExtendsClause =
        crossKeys.sorted.mkString(s"extends Cross[$crossTraitName](\"", "\", \"", "\")")
      s"""object $name0 $crossExtendsClause
         |trait $crossTraitName $extendsClause""".stripMargin
    }
    val aliasDeclaration = if (children.exists(_.useOuterModuleDir)) " outer => " else ""
    val renderModuleDir = if (useOuterModuleDir) "def moduleDir = outer.moduleDir" else ""

    s"""$typeDeclaration {$aliasDeclaration
       |
       |  $renderModuleDir
       |
       |  ${renderModuleBody(module)}
       |
       |  ${test.fold("")(renderTestModule("object", _))}
       |
       |  ${children.sortBy(_.name).map(renderModule(_)).mkString(lineSeparator * 2)}
       |}""".stripMargin
  }

  private def render[A](member: String, value: Value[A], encode: A => String): String = {
    import value.*
    if (cross.isEmpty) base.fold("")(a => s"def $member = ${encode(a)}")
    else renderCrossMatch(s"def $member = ", cross, base, encode, "")
  }
  private def render[A](
      member: String,
      values: Values[A],
      encode: A => String,
      isTask: Boolean = true,
      collection: String = "Seq"
  ): String = {
    def encodeAll(as: Seq[A]) = as.map(encode).mkString(s"$collection(", ", ", ")")
    import values.*
    if (base.isEmpty && cross.isEmpty && appendRefs.isEmpty) ""
    else {
      val stmt = StringBuilder(s"def $member = ")
      var append = false
      val invoke = if (isTask) "()" else ""
      if (appendSuper) {
        append = true
        stmt ++= s"super.$member$invoke"
      }
      if (appendRefs.nonEmpty) {
        if (append) stmt ++= " ++ " else append = true
        stmt ++= appendRefs.map(encodeModuleDep(_) ++ s".$member$invoke").mkString(" ++ ")
      }
      if (base.nonEmpty) {
        if (append) stmt ++= " ++ " else append = true
        stmt ++= encodeAll(base)
      }
      if (cross.isEmpty) stmt.result()
      else {
        val stmtEnd = if (append) {
          stmt ++= " ++ ("
          ")"
        } else ""
        renderCrossMatch(stmt.result(), cross, Some(Nil), encodeAll, stmtEnd)
      }
    }
  }
  private def renderSources(member: String, values: Values[os.RelPath]) = {
    def encodeSources(rels: Seq[os.RelPath]) = rels.map(rel =>
      if (rel.ups == 0) encodeSubPath(rel.asSubPath) else encodeRelPath("os.rel", rel)
    ).mkString("Task.Sources(", ", ", ")")
    def encodeSeq(rels: Seq[os.RelPath]) =
      rels.map(encodeRelPath("os.rel", _)).mkString("Seq(", ", ", ")")
    import values.*
    if (base.isEmpty && cross.isEmpty && appendRefs.isEmpty) ""
    else if (cross.isEmpty && !appendSuper && appendRefs.isEmpty) {
      s"def $member = ${encodeSources(base)}"
    } else {
      val stmt = StringBuilder(s"def $member = ")
      var append = false
      if (appendSuper) {
        append = true
        stmt ++= s"super.$member()"
      }
      if (appendRefs.nonEmpty) {
        if (append) stmt ++= " ++ " else append = true
        stmt ++= appendRefs.map(encodeModuleDep(_) ++ s".$member()").mkString(" ++ ")
      }
      if (base.nonEmpty) {
        val customTask = s"custom${member.capitalize}"
        stmt.insert(0, s"def $customTask = ${encodeSources(base)}$lineSeparator")
        if (append) stmt ++= " ++ " else append = true
        stmt ++= s"$customTask()"
      }
      if (cross.nonEmpty) {
        val customTask = s"customCross${member.capitalize}"
        stmt.insert(
          0,
          renderCrossMatch(
            s"def $customTask = Task.Sources((",
            cross,
            Some(Nil),
            encodeSeq,
            s")*)$lineSeparator"
          )
        )
        if (append) stmt ++= " ++ "
        stmt ++= s"$customTask()"
      }
      stmt.result()
    }
  }
  private def renderCrossMatch[A](
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

  private def encodeModuleDep(a: ModuleDep) = {
    import a.*
    val suffix = crossSuffix.getOrElse("") + childSegment.fold("")("." + _)
    ("build" +: segments.map(toScalaIdentifier)).mkString("", ".", suffix)
  }
  private def encodeMvnDep(a: MvnDep) = a.ref.getOrElse(a.toString)
  private def encodeString[A](a: A) = s"\"$a\""
  private def encodeLiteralOpt(a: Opt) = a.group.map(literalize(_)).mkString(", ")
  private def encodeOpt(a: Opt) = a.group.mkString("\"", "\", \"", "\"")
  private def encodeSubPath(a: os.SubPath) = if (a.segments.isEmpty) "os.sub" else s"\"$a\""
  private def encodeRelPath(root: String, a: os.RelPath) = {
    val ups = " / os.up" * a.ups
    val segments = if (a.segments.isEmpty) "" else a.segments.mkString(" / \"", "/", "\"")
    s"$root$ups$segments"
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
  private def encodeProperty(kv: (String, String)) = {
    val (k, v) = kv
    s"(\"$k\", ${literalize(v)})"
  }
}
