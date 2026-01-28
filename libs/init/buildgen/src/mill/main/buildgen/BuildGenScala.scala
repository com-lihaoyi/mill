package mill.main.buildgen

import mill.constants.CodeGenConstants.rootModuleAlias
import mill.constants.OutFiles.OutFiles.millBuild
import mill.internal.Util.backtickWrap
import mill.main.buildgen.ModuleSpec.*
import pprint.Util.literalize

/**
 * Generate Scala-based build/project files for Mill from given [[PakcageSpec]] and [[ModuleSpec]].
 *
 * See also [[BuildGenYaml]]
 */
object BuildGenScala {

  inline def lineSep: String = System.lineSeparator()

  def writeBuildFiles(
      workspace: os.Path,
      packages: Seq[PackageSpec],
      millVersion: String,
      millJvmVersion: String,
      millJvmOpts: Seq[String] = Nil,
      metaMvnDeps: Seq[String] = Nil
  ): Unit = {
    val rootPackage +: nestedPackages = packages.runtimeChecked
    var buildHeader = Seq(
      s"//| mill-version: $millVersion",
      s"//| mill-jvm-version: $millJvmVersion"
    )
    if (millJvmOpts.nonEmpty) {
      buildHeader :+= "//| mill-jvm-opts:"
      buildHeader ++= millJvmOpts.map("//|   - " + _)
    }
    if (metaMvnDeps.nonEmpty) {
      buildHeader :+= "//| mvnDeps:"
      buildHeader ++= metaMvnDeps.map("//|   - " + _)
    }
    println("writing build.mill")
    os.write(
      workspace / "build.mill",
      s"""${buildHeader.mkString(lineSep)}
         |${renderPackage(rootPackage)}
         |""".stripMargin
    )
    for (pkg <- nestedPackages) yield {
      val file = os.sub / pkg.dir / "package.mill"
      println(s"writing $file")
      os.write(workspace / file, renderPackage(pkg))
      file
    }
  }

  def writeMetaBuildFiles(
      workspace: os.Path,
      baseModule: Option[ModuleSpec] = None,
      depNames: Seq[(MvnDep, String)] = Nil,
      mvnDeps: Seq[String] = Nil
  ): Unit = {
    if (mvnDeps.nonEmpty) {
      val file = os.sub / "build.mill"
      println(s"writing $file")
      os.write(workspace / file, renderMetaBuildRoot(mvnDeps))
    }
    if (depNames.nonEmpty) {
      val file = os.sub / millBuild / "src/Deps.scala"
      println(s"writing $file")
      os.write(workspace / file, renderDepsObject(depNames), createFolders = true)
    }
    for (module <- baseModule) yield {
      val file = os.sub / millBuild / os.SubPath(s"src/${module.name}.scala")
      println(s"writing $file")
      os.write(
        workspace / file,
        Seq(
          "package millbuild",
          renderImports(module),
          renderBaseModule(module)
        ).mkString(lineSep * 2),
        createFolders = true
      )
    }
  }

  private def renderMetaBuildRoot(mvnDeps: Seq[String]) = {
    val mvnDeps0 = mvnDeps.map(_.replace("$MILL_VERSION", "${millVersion()}"))
    s"""package millbuild
       |import mill.*
       |import mill.meta.MillBuildRootModule
       |import mill.scalalib.*
       |object `package` extends MillBuildRootModule {
       |  def mvnDeps = Seq(${mvnDeps0.mkString(", ")})
       |}""".stripMargin
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
      s"trait $name ${renderExtendsClause(supertypes)} {",
      "  " + renderModuleBody(module),
      "  " + children.sortBy(_.name).map(renderBaseModule).mkString(lineSep * 2),
      "}"
    ).mkString(lineSep * 2)
  }

  private def renderImports(module: ModuleSpec) = {
    val imports = module.tree.flatMap(_.imports)
    ("mill.*" +: imports).distinct.sorted.map("import " + _).mkString(lineSep)
  }

  private def renderExtendsClause(supertypes: Seq[String]) = {
    if (supertypes.isEmpty) "extends Module"
    else supertypes.mkString("extends ", ", ", "")
  }

  private def renderModuleBody(module: ModuleSpec) = {
    import module.*
    val lines = Seq.newBuilder[String]
    for (a <- alias) lines += s"$a =>"
    lines += renderDefValue("moduleDir", moduleDir, identity[String])
    lines += renderDefValues("moduleDeps", moduleDeps, encodeModuleDep, isTask = false)
    lines += renderDefValues(
      "compileModuleDeps",
      compileModuleDeps,
      encodeModuleDep,
      isTask = false
    )
    lines += renderDefValues("runModuleDeps", runModuleDeps, encodeModuleDep, isTask = false)
    lines += renderDefValues("bomModuleDeps", bomModuleDeps, encodeModuleDep, isTask = false)
    lines += renderDefValues("mandatoryMvnDeps", mandatoryMvnDeps, encodeMvnDep)
    lines += renderDefValues("mvnDeps", mvnDeps, encodeMvnDep)
    lines += renderDefValues("compileMvnDeps", compileMvnDeps, encodeMvnDep)
    lines += renderDefValues("runMvnDeps", runMvnDeps, encodeMvnDep)
    lines += renderDefValues("bomMvnDeps", bomMvnDeps, encodeMvnDep)
    lines += renderDefValues("depManagement", depManagement, encodeMvnDep)
    lines += renderDefValue("scalaJSVersion", scalaJSVersion, encodeString)
    lines += renderDefValue("moduleKind", moduleKind, identity[String])
    lines += renderDefValue("scalaNativeVersion", scalaNativeVersion, encodeString)
    lines += renderDefValue("scalaVersion", scalaVersion, encodeString)
    lines += renderDefValues("scalacOptions", scalacOptions, encodeLiteralOpt)
    lines += renderDefValues("scalacPluginMvnDeps", scalacPluginMvnDeps, encodeMvnDep)
    lines += renderDefValues("javacOptions", javacOptions, encodeOpt)
    lines += renderDefValues(
      "sourcesRootFolders",
      sourcesRootFolders,
      encodeString,
      isTask = false
    )
    if (isBomModule) {
      lines += "def sources = Nil"
      lines += "def resources = Nil"
    } else {
      lines += renderDefValues("sourcesFolders", sourcesFolders, encodeString, isTask = false)
      lines += renderDefSources("sources", sources)
      lines += renderDefSources("resources", resources)
    }
    lines += renderDefValues("forkArgs", forkArgs, encodeOpt)
    lines += renderDefValue("forkWorkingDir", forkWorkingDir, identity[String])
    lines += renderDefValues("errorProneDeps", errorProneDeps, encodeMvnDep)
    lines += renderDefValues("errorProneOptions", errorProneOptions, encodeString)
    lines += renderDefValues(
      "errorProneJavacEnableOptions",
      errorProneJavacEnableOptions,
      encodeOpt
    )
    lines += renderDefValue("jmhCoreVersion", jmhCoreVersion, encodeString)
    lines += renderDefValues(
      "checkstyleProperties",
      checkstyleProperties,
      encodeProperty,
      collection = "Map"
    )
    lines += renderDefValues("checkstyleMvnDeps", checkstyleMvnDeps, encodeMvnDep)
    lines += renderDefValue("checkstyleConfig", checkstyleConfig, encodeSource)
    lines += renderDefValue("checkstyleVersion", checkstyleVersion, encodeString)
    lines += renderDefValue("scalafixConfig", scalafixConfig, encodeSome)
    lines += renderDefValues("scalafixIvyDeps", scalafixIvyDeps, encodeMvnDep)
    lines += renderDefValue("scoverageVersion", scoverageVersion, encodeString)
    lines += renderDefValue("branchCoverageMin", branchCoverageMin, encodeSome)
    lines += renderDefValue("statementCoverageMin", statementCoverageMin, encodeSome)
    lines += renderDefValues("mimaPreviousVersions", mimaPreviousVersions, encodeString)
    lines += renderDefValues("mimaPreviousArtifacts", mimaPreviousArtifacts, encodeMvnDep)
    lines += renderDefValue("mimaCheckDirection", mimaCheckDirection, identity[String])
    lines += renderDefValues("mimaBinaryIssueFilters", mimaBinaryIssueFilters, identity[String])
    lines += renderDefValues(
      "mimaBackwardIssueFilters",
      mimaBackwardIssueFilters,
      encodeIssueFiltersTuple,
      collection = "Map"
    )
    lines += renderDefValues(
      "mimaForwardIssueFilters",
      mimaForwardIssueFilters,
      encodeIssueFiltersTuple,
      collection = "Map"
    )
    lines += renderDefValues("mimaExcludeAnnotations", mimaExcludeAnnotations, encodeString)
    lines += renderDefValue("mimaReportSignatureProblems", mimaReportSignatureProblems, _.toString)
    lines += renderDefValue("artifactName", artifactName, encodeString)
    lines += renderDefValue("pomPackagingType", pomPackagingType, encodeString)
    lines += renderDefValue(
      "pomParentProject",
      pomParentProject,
      a => s"Some(${encodeArtifact(a)})"
    )
    lines += renderDefValue("pomSettings", pomSettings, encodePomSettings)
    lines += renderDefValue("publishVersion", publishVersion, encodeString)
    lines += renderDefValue("versionScheme", versionScheme, encodeSome)
    lines += renderDefValues(
      "publishProperties",
      publishProperties,
      encodeProperty,
      collection = "Map"
    )
    lines += renderDefValue("testParallelism", testParallelism, _.toString)
    lines += renderDefValue("testSandboxWorkingDir", testSandboxWorkingDir, _.toString)
    lines += renderDefValue("testFramework", testFramework, encodeTestFramework)
    lines += renderDefValues("repositories", repositories, encodeString)

    lines.result().filter(_.nonEmpty).mkString(lineSep * 2)
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
    val extendsClause = renderExtendsClause(supertypes)
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

    Seq(
      s"""$typeDeclaration {""",
      renderModuleBody(module),
      children.sortBy(_.name).map(renderModule(_)).mkString(lineSep * 2),
      "}"
    ).mkString(lineSep * 2)
  }

  private def renderDefValue[A](member: String, value: Value[A], encode: A => String) = {
    import value.*
    if (cross.isEmpty) base.fold("")(a => s"def $member = ${encode(a)}")
    else renderCrossMatch(s"def $member = ", cross, base, encode, "")
  }
  private def renderDefValues[A](
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
  private def renderDefSources(member: String, values: Values[os.RelPath]) = {
    def encode(rels: Seq[os.RelPath]) = rels.map(encodeRelPath).mkString("Task.Sources(", ", ", ")")
    def encodeSeq(rels: Seq[os.RelPath]) = rels.map(encodeRelPath).mkString("Seq(", ", ", ")")
    import values.*
    if (base.isEmpty && cross.isEmpty) ""
    else if (cross.isEmpty && !appendSuper) {
      s"def $member = ${encode(base)}"
    } else {
      val stmt = StringBuilder(s"def $member = ")
      var append = false
      if (appendSuper) {
        append = true
        stmt ++= s"super.$member()"
      }
      if (base.nonEmpty) {
        val customTask = s"custom${member.capitalize}"
        stmt.insert(0, s"def $customTask = ${encode(base)}$lineSep")
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
  private def encodeRelPath(a: os.RelPath) = {
    if (a.ups == 0) s""""$a""""
    else {
      val ups = " / os.up" * a.ups
      val segments = if (a.segments.isEmpty) "" else a.segments.mkString(" / \"", "/", "\"")
      s"os.rel$ups$segments"
    }
  }
  private def encodeSource(a: os.RelPath) = s"""Task.Source(${encodeRelPath(a)})"""
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
  private def encodeSome(a: Any) = s"Some($a)"
  private def encodeIssueFiltersTuple(k: String, v: Seq[String]) =
    s"""("$k", ${if (v.isEmpty) "Seq.empty[ProblemFilter]" else v.mkString("Seq(", ", ", ")")})"""
}
