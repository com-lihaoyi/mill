package mill.main.buildgen

import mill.main.buildgen.ModuleSpec.*

/**
 * Generate YAML-based build/project files for Mill from given [[PakcageSpec]] and [[ModuleSpec]].
 *
 * See also [[BuildGenScala]]
 */
object BuildGenYaml {

  inline def lineSep: String = System.lineSeparator()

  def writeBuildFiles(
      workspace: os.Path,
      packages: Seq[PackageSpec],
      millVersion: String,
      millJvmVersion: String,
      millJvmOpts: Seq[String] = Nil
  ): Unit = {
    val rootPackage +: nestedPackages = packages.runtimeChecked

    println("writing build.mill.yaml")
    os.write(
      workspace / "build.mill.yaml",
      renderYamlPackage(rootPackage, Some(millVersion), Some(millJvmVersion), millJvmOpts)
    )
    for (pkg <- nestedPackages) {
      val file = os.sub / pkg.dir / "package.mill.yaml"
      println(s"writing $file")
      os.write(workspace / file, renderYamlPackage(pkg, None, None, Nil))
    }
  }

  private def renderYamlPackage(
      pkg: PackageSpec,
      millVersion: Option[String],
      millJvmVersion: Option[String],
      millJvmOpts: Seq[String]
  ): String = {
    val lines = Seq.newBuilder[String]

    // Root header lines
    for (v <- millVersion) lines += s"mill-version: $v"
    for (v <- millJvmVersion) lines += s"mill-jvm-version: $v"
    if (millJvmOpts.nonEmpty) {
      lines += s"mill-jvm-opts: ${renderYamlStringList(millJvmOpts)}"
    }

    lines ++= renderYamlModuleBody(pkg.module)

    lines.result().mkString(lineSep) + lineSep
  }

  private def renderYamlModuleBody(module: ModuleSpec): Seq[String] = {
    import module.*
    val lines = Seq.newBuilder[String]

    // extends - add Module as base if there are children but no explicit supertypes
    val effectiveSupertypes =
      if (supertypes.isEmpty && children.nonEmpty) Seq("Module") else supertypes
    if (effectiveSupertypes.nonEmpty) {
      lines += renderYamlExtends(effectiveSupertypes)
    }

    // BomModule cannot have sources - set empty sources/resources when BomModule is used
    val isBomModule = effectiveSupertypes.contains("BomModule")

    // moduleDeps
    renderYamlModuleDeps("moduleDeps", moduleDeps).foreach(lines += _)
    renderYamlModuleDeps("compileModuleDeps", compileModuleDeps).foreach(lines += _)
    renderYamlModuleDeps("runModuleDeps", runModuleDeps).foreach(lines += _)
    renderYamlModuleDeps("bomModuleDeps", bomModuleDeps).foreach(lines += _)

    // mvnDeps (including mandatoryMvnDeps merged in)
    val allMvnDeps = mandatoryMvnDeps.base ++ mvnDeps.base
    val mvnDepsAppendSuper = mandatoryMvnDeps.appendSuper || mvnDeps.appendSuper
    if (allMvnDeps.nonEmpty) {
      val appendTag = if (mvnDepsAppendSuper) " !append" else ""
      lines += s"mvnDeps:$appendTag"
      for (dep <- allMvnDeps) lines += s"- ${renderYamlMvnDep(dep)}"
    }
    renderYamlMvnDepsList("compileMvnDeps", compileMvnDeps).foreach(lines += _)
    renderYamlMvnDepsList("runMvnDeps", runMvnDeps).foreach(lines += _)
    renderYamlMvnDepsList("bomMvnDeps", bomMvnDeps).foreach(lines += _)
    renderYamlMvnDepsList("depManagement", depManagement).foreach(lines += _)

    // Scala config
    renderYamlStringValue("scalaVersion", scalaVersion).foreach(lines += _)
    renderYamlStringListValues("scalacOptions", scalacOptions).foreach(lines += _)
    renderYamlMvnDepsList("scalacPluginMvnDeps", scalacPluginMvnDeps).foreach(lines += _)
    renderYamlStringValue("scalaJSVersion", scalaJSVersion).foreach(lines += _)
    renderYamlStringValue("moduleKind", moduleKind).foreach(lines += _)
    renderYamlStringValue("scalaNativeVersion", scalaNativeVersion).foreach(lines += _)

    // Java config
    renderYamlStringListValues("javacOptions", javacOptions).foreach(lines += _)

    // Sources - BomModule cannot have sources/resources, so set empty arrays
    if (isBomModule) {
      lines += "sources: []"
      lines += "resources: []"
    } else {
      renderYamlStringListValuesPlain("sourcesFolders", sourcesFolders).foreach(lines += _)
      renderYamlSourcesList("sources", sources).foreach(lines += _)
      renderYamlSourcesList("resources", resources).foreach(lines += _)
    }

    // Fork config
    renderYamlStringListValues("forkArgs", forkArgs).foreach(lines += _)

    // Error prone
    renderYamlMvnDepsList("errorProneDeps", errorProneDeps).foreach(lines += _)
    renderYamlStringListValuesPlain("errorProneOptions", errorProneOptions).foreach(lines += _)
    renderYamlStringListValues(
      "errorProneJavacEnableOptions",
      errorProneJavacEnableOptions
    ).foreach(lines += _)

    // Checkstyle
    renderYamlStringMapValues("checkstyleProperties", checkstyleProperties).foreach(lines += _)
    renderYamlMvnDepsList("checkstyleMvnDeps", checkstyleMvnDeps).foreach(lines += _)
    renderYamlRelPathValue("checkstyleConfig", checkstyleConfig).foreach(lines += _)
    renderYamlStringValue("checkstyleVersion", checkstyleVersion).foreach(lines += _)

    // Publishing
    renderYamlStringValue("artifactName", artifactName).foreach(lines += _)
    // Always render pomSettings if the module has it (YAML will override any inherited value)
    renderYamlPomSettings(pomSettings).foreach(lines += _)
    renderYamlStringValue("publishVersion", publishVersion).foreach(lines += _)

    // Testing
    renderYamlBooleanValue("testParallelism", testParallelism).foreach(lines += _)
    renderYamlBooleanValue("testSandboxWorkingDir", testSandboxWorkingDir).foreach(lines += _)
    renderYamlStringValue("testFramework", testFramework).foreach(lines += _)

    // Repositories
    renderYamlStringListValuesPlain("repositories", repositories).foreach(lines += _)

    // Nested objects (children)
    for (child <- children.sortBy(_.name)) {
      lines += ""
      lines += s"object ${child.name}:"
      for (line <- renderYamlModuleBody(child)) {
        lines += s"  $line"
      }
    }

    lines.result()
  }

  private def renderYamlExtends(supertypes: Seq[String]): String = {
    val qualified = supertypes.map(fullyQualifyType)
    if (qualified.length == 1) s"extends: ${qualified.head}"
    else s"extends: ${renderYamlStringList(qualified)}"
  }

  // Types that need to be fully qualified because they're not in the default imports
  // (mill.*, scalalib.*, javalib.*, kotlinlib.*)
  private val typesToQualify = Map(
    "ScalaJSModule" -> "mill.scalajslib.ScalaJSModule",
    "ScalaNativeModule" -> "mill.scalanativelib.ScalaNativeModule",
    "ErrorProneModule" -> "mill.javalib.errorprone.ErrorProneModule",
    "CheckstyleModule" -> "mill.javalib.checkstyle.CheckstyleModule",
    "ProjectBaseModule" -> "millbuild.ProjectBaseModule"
  )

  private def fullyQualifyType(typeName: String): String = {
    typesToQualify.getOrElse(typeName, typeName)
  }

  private def renderYamlStringList(values: Seq[String]): String =
    values.mkString("[", ", ", "]")

  private def renderYamlModuleDeps(name: String, deps: Values[ModuleDep]): Option[String] = {
    if (deps.base.isEmpty) None
    else {
      val appendTag = if (deps.appendSuper) "!append " else ""
      val depStrings = deps.base.map(renderYamlModuleDep)
      Some(s"$name: $appendTag${renderYamlStringList(depStrings)}")
    }
  }

  private def renderYamlModuleDep(dep: ModuleDep): String = {
    val base = dep.segments.mkString(".")
    // Filter out empty parentheses "()" which are invalid in YAML
    val crossPart = dep.crossSuffix.filterNot(_ == "()").getOrElse("")
    val suffix = crossPart + dep.childSegment.fold("")("." + _)
    base + suffix
  }

  private def renderYamlMvnDep(dep: MvnDep): String = {
    val binarySeparator = dep.cross match {
      case _: CrossVersion.Full => ":::"
      case _: CrossVersion.Binary => "::"
      case _ => ":"
    }
    val nameSuffix = dep.cross match {
      case v: CrossVersion.Constant => v.value
      case _ => ""
    }
    val platformSeparator = if (dep.version.isEmpty) "" else if (dep.cross.platformed) "::" else ":"
    val classifierAttr = dep.classifier.filter(_.nonEmpty).fold("")(a => s";classifier=$a")
    val typeAttr = dep.`type`.filter(t => t.nonEmpty && t != "jar").fold("")(a => s";type=$a")
    val excludeAttr = dep.excludes.map { case (org, name) => s";exclude=$org:$name" }.mkString
    s"${dep.organization}$binarySeparator${dep.name}$nameSuffix$platformSeparator${dep.version}$classifierAttr$typeAttr$excludeAttr"
  }

  private def renderYamlMvnDepsList(name: String, deps: Values[MvnDep]): Seq[String] = {
    if (deps.base.isEmpty) Nil
    else {
      val appendTag = if (deps.appendSuper) " !append" else ""
      val depLines = deps.base.map(dep => s"- ${renderYamlMvnDep(dep)}")
      s"$name:$appendTag" +: depLines
    }
  }

  private def renderYamlStringValue(name: String, value: Value[String]): Option[String] = {
    value.base.filter(_.nonEmpty).map(v => s"$name: $v")
  }

  private def renderYamlBooleanValue(name: String, value: Value[Boolean]): Option[String] = {
    value.base.map(v => s"$name: $v")
  }

  private def renderYamlStringListValues(name: String, values: Values[Opt]): Seq[String] = {
    val flatOpts = values.base.flatMap(_.group)
    if (flatOpts.isEmpty) Nil
    else {
      val appendTag = if (values.appendSuper) "!append " else ""
      Seq(s"$name: $appendTag${renderYamlStringList(flatOpts)}")
    }
  }

  private def renderYamlStringListValuesPlain(name: String, values: Values[String]): Seq[String] = {
    if (values.base.isEmpty) Nil
    else {
      val appendTag = if (values.appendSuper) "!append " else ""
      Seq(s"$name: $appendTag${renderYamlStringList(values.base)}")
    }
  }

  private def renderYamlSourcesList(name: String, values: Values[os.RelPath]): Seq[String] = {
    if (values.base.isEmpty) Nil
    else {
      val paths = values.base.map(_.toString)
      Seq(s"$name: ${renderYamlStringList(paths)}")
    }
  }

  private def renderYamlStringMapValues(
      name: String,
      values: Values[(String, String)]
  ): Seq[String] = {
    if (values.base.isEmpty) Nil
    else {
      val entries = values.base.map((k, v) => s"  $k: $v")
      s"$name:" +: entries
    }
  }

  private def renderYamlRelPathValue(name: String, value: Value[os.RelPath]): Option[String] = {
    value.base.map(v => s"$name: $v")
  }

  private def renderYamlPomSettings(value: Value[PomSettings]): Seq[String] = {
    value.base.flatMap { pom =>
      val content = Seq.newBuilder[String]
      if (pom.description.nonEmpty)
        content += s"  description: ${yamlEscapeString(pom.description)}"
      if (pom.organization.nonEmpty) content += s"  organization: ${pom.organization}"
      if (pom.url.nonEmpty && !containsPlaceholder(pom.url)) content += s"  url: ${pom.url}"
      if (pom.licenses.nonEmpty) {
        val licenseIds = pom.licenses.flatMap { l =>
          if (l.id.nonEmpty) Some(l.id)
          else if (l.name.nonEmpty) Some(l.name)
          else None
        }.map(yamlEscapeStringInList)
        if (licenseIds.nonEmpty) {
          content += s"  licenses: ${licenseIds.mkString("[", ", ", "]")}"
        }
      }
      val vc = pom.versionControl
      // Filter out URLs that contain unresolved placeholders like ${scm.url}
      val vcUrl = vc.browsableRepository.orElse(vc.connection).filterNot(containsPlaceholder)
      vcUrl.foreach { url =>
        content += s"  versionControl: $url"
      }
      if (pom.developers.nonEmpty) {
        content += "  developers:"
        for (dev <- pom.developers) {
          val parts = Seq.newBuilder[String]
          if (dev.name.nonEmpty) parts += s"name: ${yamlEscapeString(dev.name)}"
          if (dev.url.nonEmpty && !containsPlaceholder(dev.url)) parts += s"url: ${dev.url}"
          dev.organization.foreach(o => parts += s"organization: ${yamlEscapeString(o)}")
          content += s"  - {${parts.result().mkString(", ")}}"
        }
      }
      val contentLines = content.result()
      // Only output pomSettings if there's actual content
      if (contentLines.isEmpty) None
      else Some("pomSettings:" +: contentLines)
    }.getOrElse(Nil)
  }

  private def containsPlaceholder(s: String): Boolean = s.contains("${") && s.contains("}")

  private def yamlEscapeString(s: String): String = {
    // Simple escaping for YAML strings - quote if contains special chars
    if (
      s.contains(":") || s.contains("#") || s.contains("\"") || s.contains("'") ||
      s.contains("\n") || s.startsWith(" ") || s.endsWith(" ") || s.contains(",")
    ) {
      "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    } else {
      s
    }
  }

  private def yamlEscapeStringInList(s: String): String = {
    // For list items, we need to quote strings containing commas, brackets, or other special chars
    if (
      s.contains(",") || s.contains("[") || s.contains("]") || s.contains(":") ||
      s.contains("#") || s.contains("\"") || s.contains("'") || s.contains("\n") ||
      s.startsWith(" ") || s.endsWith(" ")
    ) {
      "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    } else {
      s
    }
  }
}
