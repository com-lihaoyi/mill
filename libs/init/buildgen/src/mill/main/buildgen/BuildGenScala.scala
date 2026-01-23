package mill.main.buildgen

import mill.constants.CodeGenConstants.rootModuleAlias
import mill.constants.OutFiles.OutFiles.millBuild
import mill.internal.Util.backtickWrap
import mill.main.buildgen.BuildInfo.millVersion
import mill.main.buildgen.BuildGenUtil.*
import mill.main.buildgen.ModuleSpec.*
import pprint.Util.literalize

/**
 * Generate Scala-based build/project files for Mill from given [[PakcageSpec]] and [[ModuleSpec]].
 *
 * See also [[BuildGenYaml]]
 */
object BuildGenScala extends BuildGen {

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
        scalacPluginMvnDeps
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
          imports = "import millbuild.*" +: imports,
          mvnDeps = withRefs(mvnDeps),
          compileMvnDeps = withRefs(compileMvnDeps),
          runMvnDeps = withRefs(runMvnDeps),
          bomMvnDeps = withRefs(bomMvnDeps),
          depManagement = withRefs(depManagement),
          errorProneDeps = withRefs(errorProneDeps),
          scalacPluginMvnDeps = withRefs(scalacPluginMvnDeps)
        )
      })
    )
    (names.toSeq, packages0)
  }

  override def writeBuildFiles(
      baseDir: os.Path,
      packages: Seq[PackageSpec],
      merge: Boolean = false,
      baseModule: Option[ModuleSpec] = None,
      millJvmVersion: Option[String] = None,
      millJvmOpts: Seq[String] = Nil,
      depNames: Seq[(MvnDep, String)] = Nil
  ): Seq[os.Path] = {
    var packages0 = fillPackages(packages).sortBy(_.dir)
    packages0 = if (merge) Seq(mergePackages(packages0.head, packages0.tail)) else packages0
    removeExistingBuildFiles()

    if (depNames.nonEmpty) {
      val file = os.sub / millBuild / "src/Deps.scala"
      println(s"writing $file")
      os.write(baseDir / file, renderDepsObject(depNames), createFolders = true)
    }
    val baseFile = for (module <- baseModule) yield {
      val file = os.sub / millBuild / os.SubPath(s"src/${module.name}.scala")
      println(s"writing $file")
      os.write(
        baseDir / file,
        Seq(
          "package millbuild",
          renderImports(module),
          renderBaseModule(module)
        ).mkString(lineSep * 2),
        createFolders = true
      )
      file
    }
    val rootPackage +: nestedPackages = packages0.runtimeChecked
    val millJvmVersion0 = resolveMillJvmVersion(millJvmVersion)
    val millJvmOptsLine = if (millJvmOpts.isEmpty) ""
    else millJvmOpts.mkString("//| mill-jvm-opts: [\"", "\", \"", s"\"]")
    println("writing build.mill")
    os.write(
      baseDir / "build.mill",
      Seq(
        s"//| mill-version: $millVersion",
        s"//| mill-jvm-version: $millJvmVersion0",
        millJvmOptsLine,
        renderPackage(rootPackage)
      ).filter(_.nonEmpty).mkString(lineSep)
    )
    val subFiles = for (pkg <- nestedPackages) yield {
      val file = os.sub / pkg.dir / "package.mill"
      println(s"writing $file")
      os.write(baseDir / file, renderPackage(pkg))
      file
    }
    (baseFile.toSeq ++ subFiles).map(baseDir / _)
  }

  private def renderDepsObject(depNames: Seq[(MvnDep, String)]) = {
    Seq(
      "package millbuild",
      "",
      "import mill.javalib.*",
      "",
      "object Deps {",
      "  " + depNames.sortBy(_._2).map((d, n) => s"val $n = $d").mkString(lineSep),
      "}"
    ).mkString(lineSep)
  }

  private def renderBaseModule(module: ModuleSpec): String = {
    import module.*
    Seq(
      s"trait $name ${renderExtendsClause(supertypes ++ mixins)} {",
      "  " + renderModuleBody(module),
      "  " + children.sortBy(_.name).map(renderBaseModule).mkString(lineSep * 2),
      "}"
    ).mkString(lineSep * 2)
  }

  private def renderModuleBody(module: ModuleSpec) = {
    import module.*
    val renderModuleDir = if (useOuterModuleDir) "def moduleDir = outer.moduleDir" else ""
    Seq(
      renderModuleDir,
      render("moduleDeps", moduleDeps, encodeModuleDep, isTask = false),
      render("compileModuleDeps", compileModuleDeps, encodeModuleDep, isTask = false),
      render("runModuleDeps", runModuleDeps, encodeModuleDep, isTask = false),
      render("bomModuleDeps", bomModuleDeps, encodeModuleDep, isTask = false),
      render("mandatoryMvnDeps", mandatoryMvnDeps, encodeMvnDep),
      render("mvnDeps", mvnDeps, encodeMvnDep),
      render("compileMvnDeps", compileMvnDeps, encodeMvnDep),
      render("runMvnDeps", runMvnDeps, encodeMvnDep),
      render("bomMvnDeps", bomMvnDeps, encodeMvnDep),
      render("depManagement", depManagement, encodeMvnDep),
      render("scalaJSVersion", scalaJSVersion, encodeString),
      render("moduleKind", moduleKind, identity[String]),
      render("scalaNativeVersion", scalaNativeVersion, encodeString),
      render("scalaVersion", scalaVersion, encodeString),
      render("scalacOptions", scalacOptions, encodeLiteralOpt),
      render("scalacPluginMvnDeps", scalacPluginMvnDeps, encodeMvnDep),
      render("javacOptions", javacOptions, encodeOpt),
      render("sourcesRootFolders", sourcesRootFolders, encodeString, isTask = false),
      render("sourcesFolders", sourcesFolders, encodeString, isTask = false),
      renderSources("sources", sources),
      renderSources("resources", resources),
      render("forkArgs", forkArgs, encodeOpt),
      render("forkWorkingDir", forkWorkingDir, encodeRelPath("moduleDir", _)),
      render("errorProneDeps", errorProneDeps, encodeMvnDep),
      render("errorProneOptions", errorProneOptions, encodeString),
      render("errorProneJavacEnableOptions", errorProneJavacEnableOptions, encodeOpt),
      render("artifactName", artifactName, encodeString),
      render("pomPackagingType", pomPackagingType, encodeString),
      render("pomParentProject", pomParentProject, a => s"Some(${encodeArtifact(a)})"),
      render("pomSettings", pomSettings, encodePomSettings),
      render("publishVersion", publishVersion, encodeString),
      render("versionScheme", versionScheme, a => s"Some($a)"),
      render("publishProperties", publishProperties, encodeProperty, collection = "Map"),
      render("testParallelism", testParallelism, _.toString),
      render("testSandboxWorkingDir", testSandboxWorkingDir, _.toString),
      render("testFramework", testFramework, encodeTestFramework),
      render("repositories", repositories, encodeString)
    ).mkString(lineSep * 2)

  }

  private def renderPackage(pkg: PackageSpec) = {
    import pkg.*
    val namespace = (rootModuleAlias +: dir.segments.map(backtickWrap)).mkString(".")
    Seq(
      s"package $namespace",
      renderImports(module),
      renderModule(module, isPackageRoot = true)
    ).mkString("\n\n")
  }

  private def renderModule(module: ModuleSpec, isPackageRoot: Boolean = false): String = {
    import module.*
    val name0 = if (isPackageRoot) "`package`" else backtickWrap(name)
    val extendsClause = renderExtendsClause(supertypes ++ mixins)
    val typeDeclaration = if (crossKeys.isEmpty) s"object $name0 $extendsClause"
    else {
      val crossTraitName = backtickWrap(name.split("\\W") match {
        case Array("") => s"`${name}Module`"
        case parts => parts.map(_.capitalize).mkString("", "", "Module")
      })
      val crossExtendsClause =
        crossKeys.sorted.mkString(s"extends Cross[$crossTraitName](\"", "\", \"", "\")")
      s"object $name0 $crossExtendsClause\ntrait $crossTraitName $extendsClause"
    }
    val aliasDeclaration = if (children.exists(_.useOuterModuleDir)) " outer => " else ""

    Seq(
      s"""$typeDeclaration {$aliasDeclaration""",
      renderModuleBody(module),
      children.sortBy(_.name).map(renderModule(_)).mkString(lineSep * 2),
      "}"
    ).mkString(lineSep * 2)
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
    if (empty) s"def $member = $collection()"
    else if (base.isEmpty && cross.isEmpty && appendRefs.isEmpty) ""
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
      if (rel.ups == 0) encodeString(rel.toString) else encodeRelPath("os.rel", rel)
    ).mkString("Task.Sources(", ", ", ")")
    def encodeSeq(rels: Seq[os.RelPath]) =
      rels.map(encodeRelPath("os.rel", _)).mkString("Seq(", ", ", ")")
    import values.*
    if (empty) s"def $member = Task.Sources()"
    else if (base.isEmpty && cross.isEmpty && appendRefs.isEmpty) ""
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
        stmt.insert(0, s"def $customTask = ${encodeSources(base)}$lineSep")
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
            s")*)$lineSep"
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
    (rootModuleAlias +: segments.map(backtickWrap)).mkString("", ".", suffix)
  }
  private def encodeMvnDep(a: MvnDep) = a.ref.getOrElse(a.toString)
  private def encodeString(s: String) = s"\"$s\""
  private def encodeLiteralOpt(a: Opt) = a.group.map(literalize(_)).mkString(", ")
  private def encodeOpt(a: Opt) = a.group.mkString("\"", "\", \"", "\"")
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
  private def encodeTestFramework(s: String) =
    if (s.isEmpty) "sys.error(\"no test framework\")" else encodeString(s)
}
