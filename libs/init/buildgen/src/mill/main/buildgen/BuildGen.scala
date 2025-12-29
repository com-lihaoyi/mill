package mill.main.buildgen

import mill.constants.CodeGenConstants.rootModuleAlias
import mill.constants.OutFiles.OutFiles.millBuild
import mill.init.Util
import mill.internal.Util.backtickWrap
import mill.main.buildgen.BuildInfo.millVersion
import mill.main.buildgen.ModuleSpec.*
import pprint.Util.literalize

import java.lang.System.lineSeparator

object BuildGen {

  def withNamedDeps(packages: Seq[PackageSpec]): (Seq[(MvnDep, String)], Seq[PackageSpec]) = {
    val names = packages.flatMap(_.module.tree).flatMap { module =>
      import module.*
      Seq(
        mandatoryMvnDeps,
        mvnDeps,
        compileMvnDeps,
        runMvnDeps,
        bomMvnDeps,
        depManagement,
        errorProneDeps,
        scalacPluginMvnDeps,
        scalafixIvyDeps
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
    if (names.isEmpty) return (Nil, packages)
    val lookup = names.lift.andThen(_.map(ref => s"Deps.$ref"))
    def withRefs(values: Values[MvnDep]) = values.copy(
      base = values.base.map(dep => dep.copy(ref = lookup(dep))),
      cross = values.cross.map((k, v) => (k, v.map(dep => dep.copy(ref = lookup(dep)))))
    )
    val packages0 = packages.map(pkg =>
      pkg.copy(module = pkg.module.recMap { module =>
        import module.*
        module.copy(
          imports = "millbuild.*" +: imports,
          mvnDeps = withRefs(mvnDeps),
          compileMvnDeps = withRefs(compileMvnDeps),
          runMvnDeps = withRefs(runMvnDeps),
          bomMvnDeps = withRefs(bomMvnDeps),
          depManagement = withRefs(depManagement),
          errorProneDeps = withRefs(errorProneDeps),
          scalacPluginMvnDeps = withRefs(scalacPluginMvnDeps),
          scalafixIvyDeps = withRefs(scalafixIvyDeps)
        )
      })
    )
    (names.toSeq, packages0)
  }

  def withBaseModule(
      packages: Seq[PackageSpec],
      baseWithTestHierarchy: (String, String)*
  ): Option[(ModuleSpec, Seq[PackageSpec])] = {
    def parentValue[A](a: Value[A], b: Value[A]) = Value(
      if (a.base == b.base) a.base else None,
      a.cross.intersect(b.cross)
    )
    def parentValues[A](a: Values[A], b: Values[A]) = Values(
      a.base.intersect(b.base),
      a.cross.flatMap { (k, a) =>
        b.cross.collectFirst {
          case (`k`, b) => (k, a.intersect(b))
        }.filter(_._2.nonEmpty)
      },
      a.appendSuper && b.appendSuper
    )
    def parentModule(name: String, hierarchy: Seq[String])(a: ModuleSpec, b: ModuleSpec) =
      ModuleSpec(
        name = name,
        imports = (a.imports ++ b.imports).distinct.filter(!_.startsWith("millbuild")),
        supertypes = {
          val (aPrefix, aSuffix) = a.supertypes.splitAt(a.supertypes.indexWhere(hierarchy.contains))
          val (bPrefix, bSuffix) = b.supertypes.splitAt(b.supertypes.indexWhere(hierarchy.contains))
          (aPrefix.intersect(bPrefix), aSuffix.intersect(bSuffix)) match {
            case (Nil, Nil) => hierarchy.take(1)
            case (prefix, suffix) if suffix.exists(hierarchy.contains) => prefix ++ suffix
            case (prefix, suffix) => prefix ++ (hierarchy.head +: suffix)
          }
        },
        codeBlocks = a.codeBlocks.intersect(b.codeBlocks),
        repositories = parentValues(a.repositories, b.repositories),
        forkArgs = parentValues(a.forkArgs, b.forkArgs),
        mandatoryMvnDeps = parentValues(a.mandatoryMvnDeps, b.mandatoryMvnDeps),
        mvnDeps = parentValues(a.mvnDeps, b.mvnDeps),
        compileMvnDeps = parentValues(a.compileMvnDeps, b.compileMvnDeps),
        runMvnDeps = parentValues(a.runMvnDeps, b.runMvnDeps),
        bomMvnDeps = parentValues(a.bomMvnDeps, b.bomMvnDeps),
        depManagement = parentValues(a.depManagement, b.depManagement),
        javacOptions = parentValues(a.javacOptions, b.javacOptions),
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
        testFramework = parentValue(a.testFramework, b.testFramework),
        scalafixIvyDeps = parentValues(a.scalafixIvyDeps, b.scalafixIvyDeps),
        scoverageVersion = parentValue(a.scoverageVersion, b.scoverageVersion),
        branchCoverageMin = parentValue(a.branchCoverageMin, b.branchCoverageMin),
        statementCoverageMin = parentValue(a.statementCoverageMin, b.statementCoverageMin)
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
    def extendModule(a: ModuleSpec, parent: ModuleSpec, hierarchy: Seq[String]): ModuleSpec =
      a.copy(
        supertypes = {
          val (prefix, suffix) = a.supertypes.splitAt(a.supertypes.indexWhere(hierarchy.contains))
          (prefix ++ (parent.name +: suffix)).diff(parent.supertypes)
        },
        codeBlocks = a.codeBlocks.diff(parent.codeBlocks),
        repositories = extendValues(a.repositories, parent.repositories),
        forkArgs = extendValues(a.forkArgs, parent.forkArgs),
        mandatoryMvnDeps = extendValues(a.mandatoryMvnDeps, parent.mandatoryMvnDeps),
        mvnDeps = extendValues(a.mvnDeps, parent.mvnDeps),
        compileMvnDeps = extendValues(a.compileMvnDeps, parent.compileMvnDeps),
        runMvnDeps = extendValues(a.runMvnDeps, parent.runMvnDeps),
        bomMvnDeps = extendValues(a.bomMvnDeps, parent.bomMvnDeps),
        depManagement = extendValues(a.depManagement, parent.depManagement),
        javacOptions = extendValues(a.javacOptions, parent.javacOptions),
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
        testFramework = extendValue(a.testFramework, parent.testFramework),
        scalafixIvyDeps = extendValues(a.scalafixIvyDeps, parent.scalafixIvyDeps),
        scoverageVersion = extendValue(a.scoverageVersion, parent.scoverageVersion),
        branchCoverageMin = extendValue(a.branchCoverageMin, parent.branchCoverageMin),
        statementCoverageMin = extendValue(a.statementCoverageMin, parent.statementCoverageMin)
      )
    val (baseHierarchy, testHierarchy) = baseWithTestHierarchy.unzip
    def canExtend(module: ModuleSpec) = module.supertypes.exists(baseHierarchy.contains)
    def isTestModule(module: ModuleSpec) = module.supertypes.exists(testHierarchy.contains)
    def recExtendModule(a: ModuleSpec, parent: ModuleSpec): ModuleSpec = {
      var module = a
      var (tests, children) = module.children.partition(isTestModule)
      if (canExtend(module)) {
        module = module.copy(imports = "millbuild.*" +: module.imports)
        module = extendModule(module, parent, baseHierarchy)
        if (parent.children.nonEmpty) {
          tests = tests.map(extendModule(_, parent.children.head, testHierarchy))
        }
      }
      children = children.map(recExtendModule(_, parent))
      module.copy(children = tests ++ children).withAlias()
    }

    val extendingModules = packages.flatMap(_.module.tree).filter(canExtend)
    Option.when(extendingModules.length > 1) {
      var baseModule = extendingModules.reduce(parentModule("ProjectBaseModule", baseHierarchy))
      val testModule = extendingModules.flatMap(_.children.filter(isTestModule))
        .reduceOption(parentModule("Tests", testHierarchy)).map { module =>
          var supertypes = module.supertypes
          var codeBlocks = module.codeBlocks
          val i = baseHierarchy.indexWhere(baseModule.supertypes.contains)
          val j = testHierarchy.indexWhere(module.supertypes.contains)
          if (i < j) {
            supertypes = supertypes.updated(j, testHierarchy(i))
          }
          if (
            supertypes.contains("ScalafixModule") &&
            !baseModule.supertypes.contains("ScalafixModule")
          ) {
            codeBlocks = codeBlocks.filter(!_.contains("def scalafix"))
          }
          if (
            supertypes.contains("ScoverageTests") &&
            !baseModule.supertypes.contains("ScoverageModule")
          ) {
            supertypes = supertypes.filter(_ != "ScoverageTests")
          }
          module.copy(supertypes = supertypes, codeBlocks = codeBlocks)
        }
      baseModule = baseModule.copy(children = testModule.toSeq).withAlias()
      val packages0 =
        packages.map(pkg => pkg.copy(module = recExtendModule(pkg.module, baseModule)))
      (baseModule, packages0)
    }
  }

  def writeBuildFiles(
      packages: Seq[PackageSpec],
      merge: Boolean,
      depNames: Seq[(MvnDep, String)],
      baseModule: Option[ModuleSpec],
      millJvmVersion: String = "system",
      millJvmOpts: Seq[String] = Nil,
      mvnDeps: Seq[String] = Nil
  ): Unit = {
    var packages0 = fillPackages(packages).sortBy(_.dir)
    packages0 = if (merge) Seq(mergePackages(packages0.head, packages0.tail)) else packages0
    val existingBuildFiles = Util.buildFiles(os.pwd)
    if (existingBuildFiles.nonEmpty) {
      println("removing existing build files ...")
      for (file <- existingBuildFiles) do os.remove(file)
    }

    if (depNames.nonEmpty) {
      val file = os.sub / millBuild / "src/Deps.scala"
      println(s"writing $file")
      os.write(os.pwd / file, renderDepsObject(depNames), createFolders = true)
    }
    for (module <- baseModule) do {
      val file = os.sub / millBuild / os.SubPath(s"src/${module.name}.scala")
      println(s"writing $file")
      os.write(
        os.pwd / file,
        s"""package millbuild
           |${renderImports(module)}
           |${renderBaseModule(module)}""".stripMargin,
        createFolders = true
      )
    }
    val rootPackage +: nestedPackages = packages0: @unchecked
    println("writing build.mill")
    os.write(
      os.pwd / "build.mill",
      s"""${renderBuildHeader(millJvmVersion, millJvmOpts, mvnDeps)}
         |${renderPackage(rootPackage)}
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

  private def renderDepsObject(depNames: Seq[(MvnDep, String)]) = {
    s"""package millbuild
       |import mill.javalib.*
       |object Deps {
       |
       |  ${depNames.sortBy(_._2).map((d, n) => s"val $n = $d").mkString(lineSeparator)}
       |}""".stripMargin
  }

  private def renderBaseModule(module: ModuleSpec): String = {
    import module.*
    s"""trait $name ${renderExtendsClause(supertypes)} {
       |
       |  ${renderModuleBody(module)}
       |
       |  ${children.sortBy(_.name).map(renderBaseModule).mkString(lineSeparator * 2)}
       |}""".stripMargin
  }

  private def renderImports(module: ModuleSpec) = {
    val imports = module.tree.flatMap(_.imports)
    ("mill.*" +: imports).distinct.sorted.mkString("import ", s"${lineSeparator}import ", "")
  }

  private def renderExtendsClause(supertypes: Seq[String]) = {
    if (supertypes.isEmpty) "extends Module"
    else supertypes.mkString("extends ", ", ", "")
  }

  private def renderModuleBody(module: ModuleSpec) = {
    import module.*
    val aliasDeclaration = alias.fold("")(_ + " =>")
    s"""$aliasDeclaration
       |
       |${render("moduleDeps", moduleDeps, encodeModuleDep, isTask = false)}
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
       |${render("forkArgs", forkArgs, encodeOpt)}
       |
       |${render("errorProneDeps", errorProneDeps, encodeMvnDep)}
       |
       |${render("errorProneOptions", errorProneOptions, encodeString)}
       |
       |${render("errorProneJavacEnableOptions", errorProneJavacEnableOptions, encodeOpt)}
       |
       |${render("scalafixIvyDeps", scalafixIvyDeps, encodeMvnDep)}
       |
       |${render("scoverageVersion", scoverageVersion, encodeString)}
       |
       |${render("branchCoverageMin", branchCoverageMin, a => s"Some($a")}
       |
       |${render("statementCoverageMin", statementCoverageMin, a => s"Some($a")}
       |
       |${codeBlocks.mkString(lineSeparator * 2)}
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
       |${render("testFramework", testFramework, encodeTestFramework)}
       |
       |${render("repositories", repositories, encodeString)}
       |""".stripMargin
  }

  private def renderBuildHeader(
      millJvmVersion: String,
      millJvmOpts: Seq[String],
      mvnDeps: Seq[String]
  ) = {
    def seq[A](key: String, values: Seq[A]) = if (values.isEmpty) ""
    else values.mkString(s"//| $key:$lineSeparator//| - ", s"$lineSeparator//| - ", lineSeparator)
    val millJvmOptsEntry = seq("mill-jvm-opts", millJvmOpts.map(encodeString))
    val mvnDepsEntry = seq("mvnDeps", mvnDeps)
    s"""//| mill-version: $millVersion
       |//| mill-jvm-version: $millJvmVersion
       |$millJvmOptsEntry$mvnDepsEntry""".stripMargin
  }

  private def renderPackage(pkg: PackageSpec) = {
    import pkg.*
    val namespace = (rootModuleAlias +: dir.segments.map(backtickWrap)).mkString(".")
    s"""package $namespace
       |${renderImports(module)}
       |${renderModule(module, isPackageRoot = true)}
       |""".stripMargin
  }

  private def renderModule(module: ModuleSpec, isPackageRoot: Boolean = false): String = {
    import module.*
    val name0 = if (isPackageRoot) "`package`" else backtickWrap(name)
    val extendsClause = renderExtendsClause(supertypes)
    val typeDeclaration = if (crossKeys.isEmpty) s"object $name0 $extendsClause"
    else {
      val crossTraitName = backtickWrap(name.split("\\W") match {
        case Array("") => s"`${name}Module`"
        case parts => parts.map(_.capitalize).mkString("", "", "Module")
      })
      val crossExtendsClause =
        crossKeys.sorted.mkString(s"extends Cross[$crossTraitName](\"", "\", \"", "\")")
      s"""object $name0 $crossExtendsClause
         |trait $crossTraitName $extendsClause""".stripMargin
    }

    s"""$typeDeclaration {
       |
       |  ${renderModuleBody(module)}
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
    if (base.isEmpty && cross.isEmpty) ""
    else {
      val stmt = StringBuilder(s"def $member = ")
      var append = false
      val invoke = if (isTask) "()" else ""
      if (appendSuper) {
        append = true
        stmt ++= s"super.$member$invoke"
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
    (rootModuleAlias +: moduleDir.segments.map(backtickWrap)).mkString("", ".", suffix)
  }
  private def encodeMvnDep(a: MvnDep) = a.ref.getOrElse(a.toString)
  private def encodeString(s: String) = s"\"$s\""
  private def encodeLiteralOpt(a: Opt) = a.group.map(literalize(_)).mkString(", ")
  private def encodeOpt(a: Opt) = a.group.mkString("\"", "\", \"", "\"")
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
  private def encodeTestFramework(s: String) =
    if (s.isEmpty) "sys.error(\"no test framework\")" else encodeString(s)
}
