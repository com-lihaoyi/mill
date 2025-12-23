package mill.main.buildgen

import mill.main.buildgen.ModuleSpec.*

import java.lang.System.lineSeparator

object BuildGen {

  def buildFiles(workspace: os.Path): Seq[os.Path] = {
    val skip = Seq(workspace / "mill-build", workspace / "out")
    os.walk.stream(workspace, skip = skip.contains).filter(path =>
      os.isFile(path) && (
        path.last == "build.mill" || path.last == "package.mill" ||
          path.last == "build.mill.yaml" || path.last == "package.mill.yaml"
      )
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
        os.isFile(path) && (
          path.last == "build.mill" || path.last == "package.mill" ||
            path.last == "build.mill.yaml" || path.last == "package.mill.yaml"
        )
      ) ++ (
        if (os.exists(os.pwd / "mill-build")) os.walk(os.pwd / "mill-build").filter(os.isFile)
        else Nil
      )
    if (existingBuildFiles.nonEmpty) {
      println("removing existing build files ...")
      for (file <- existingBuildFiles) do os.remove(file)
    }

    val rootPackage +: nestedPackages = packages0: @unchecked
    val millJvmOptsLine = if (millJvmOpts.isEmpty) ""
    else millJvmOpts.mkString("mill-jvm-opts: [\"", "\", \"", s"\"]$lineSeparator")
    println("writing build.mill.yaml")
    os.write(
      os.pwd / "build.mill.yaml",
      s"""mill-version: SNAPSHOT
         |mill-jvm-version: $millJvmVersion
         |$millJvmOptsLine${renderYamlPackage(rootPackage)}
         |""".stripMargin
    )
    for (pkg <- nestedPackages) do {
      val file = os.sub / pkg.dir / "package.mill.yaml"
      println(s"writing $file")
      os.write(os.pwd / file, renderYamlPackage(pkg))
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

  private def renderYamlPackage(pkg: PackageSpec): String = {
    renderYamlModule(pkg.module, indent = 0, isRoot = true)
  }

  private def renderYamlModule(module: ModuleSpec, indent: Int, isRoot: Boolean): String = {
    val sb = new StringBuilder
    val prefix = "  " * indent

    // Render extends
    val allSupertypes = module.supertypes ++ module.mixins
    if (allSupertypes.nonEmpty) {
      if (allSupertypes.size == 1) {
        sb ++= s"${prefix}extends: ${allSupertypes.head}$lineSeparator"
      } else {
        sb ++= s"${prefix}extends: [${allSupertypes.mkString(", ")}]$lineSeparator"
      }
    }

    // Render module deps
    renderYamlModuleDeps(sb, prefix, "moduleDeps", module.moduleDeps)
    renderYamlModuleDeps(sb, prefix, "compileModuleDeps", module.compileModuleDeps)
    renderYamlModuleDeps(sb, prefix, "runModuleDeps", module.runModuleDeps)
    renderYamlModuleDeps(sb, prefix, "bomModuleDeps", module.bomModuleDeps)

    // Render maven deps
    renderYamlMvnDeps(sb, prefix, "mvnDeps", combineMvnDeps(module.mandatoryMvnDeps, module.mvnDeps))
    renderYamlMvnDeps(sb, prefix, "compileMvnDeps", module.compileMvnDeps)
    renderYamlMvnDeps(sb, prefix, "runMvnDeps", module.runMvnDeps)
    renderYamlMvnDeps(sb, prefix, "bomMvnDeps", module.bomMvnDeps)
    renderYamlMvnDeps(sb, prefix, "depManagement", module.depManagement)
    renderYamlMvnDeps(sb, prefix, "scalacPluginMvnDeps", module.scalacPluginMvnDeps)

    // Render scala/java configuration
    renderYamlValue(sb, prefix, "scalaVersion", module.scalaVersion)
    renderYamlValues(sb, prefix, "scalacOptions", module.scalacOptions, encodeYamlOpt)
    renderYamlValues(sb, prefix, "javacOptions", module.javacOptions, encodeYamlOpt)
    renderYamlValue(sb, prefix, "scalaJSVersion", module.scalaJSVersion)
    renderYamlValue(sb, prefix, "moduleKind", module.moduleKind)
    renderYamlValue(sb, prefix, "scalaNativeVersion", module.scalaNativeVersion)

    // Render sources and resources
    renderYamlPaths(sb, prefix, "sources", module.sources)
    renderYamlPaths(sb, prefix, "resources", module.resources)
    renderYamlSubPaths(sb, prefix, "sourcesRootFolders", module.sourcesRootFolders)
    renderYamlSubPaths(sb, prefix, "sourcesFolders", module.sourcesFolders)

    // Render fork configuration
    renderYamlValues(sb, prefix, "forkArgs", module.forkArgs, encodeYamlOpt)
    renderYamlForkWorkingDir(sb, prefix, module.forkWorkingDir)

    // Render repositories
    renderYamlStrings(sb, prefix, "repositories", module.repositories)

    // Render error prone configuration
    renderYamlMvnDeps(sb, prefix, "errorProneDeps", module.errorProneDeps)
    renderYamlStrings(sb, prefix, "errorProneOptions", module.errorProneOptions)
    renderYamlValues(sb, prefix, "errorProneJavacEnableOptions", module.errorProneJavacEnableOptions, encodeYamlOpt)

    // Render publishing configuration
    renderYamlValue(sb, prefix, "artifactName", module.artifactName)
    renderYamlValue(sb, prefix, "pomPackagingType", module.pomPackagingType)
    renderYamlPomParentProject(sb, prefix, module.pomParentProject)
    renderYamlPomSettings(sb, prefix, module.pomSettings)
    renderYamlValue(sb, prefix, "publishVersion", module.publishVersion)
    renderYamlVersionScheme(sb, prefix, module.versionScheme)
    renderYamlPublishProperties(sb, prefix, module.publishProperties)

    // Render test configuration
    renderYamlBoolValue(sb, prefix, "testParallelism", module.testParallelism)
    renderYamlBoolValue(sb, prefix, "testSandboxWorkingDir", module.testSandboxWorkingDir)

    // Render test submodule
    module.test.foreach { testSpec =>
      sb ++= s"${prefix}object test:$lineSeparator"
      sb ++= renderYamlModule(testSpec, indent + 1, isRoot = false)
    }

    // Render child modules
    for (child <- module.children.sortBy(_.name)) {
      sb ++= s"${prefix}object ${child.name}:$lineSeparator"
      sb ++= renderYamlModule(child, indent + 1, isRoot = false)
    }

    sb.result()
  }

  private def combineMvnDeps(a: Values[MvnDep], b: Values[MvnDep]): Values[MvnDep] = {
    Values(
      a.base ++ b.base,
      (a.cross ++ b.cross).groupMapReduce(_._1)(_._2)(_ ++ _).toSeq,
      a.appendSuper || b.appendSuper,
      a.appendRefs ++ b.appendRefs
    )
  }

  private def renderYamlModuleDeps(
      sb: StringBuilder,
      prefix: String,
      name: String,
      deps: Values[ModuleDep]
  ): Unit = {
    if (deps.base.nonEmpty) {
      val depStrings = deps.base.map(encodeYamlModuleDep)
      sb ++= s"$prefix$name: [${depStrings.mkString(", ")}]$lineSeparator"
    }
  }

  private def renderYamlMvnDeps(
      sb: StringBuilder,
      prefix: String,
      name: String,
      deps: Values[MvnDep]
  ): Unit = {
    if (deps.base.nonEmpty) {
      sb ++= s"$prefix$name:$lineSeparator"
      for (dep <- deps.base) {
        sb ++= s"$prefix- ${encodeYamlMvnDep(dep)}$lineSeparator"
      }
    }
  }

  private def renderYamlValue[A](
      sb: StringBuilder,
      prefix: String,
      name: String,
      value: Value[A]
  ): Unit = {
    value.base.foreach { v =>
      val encoded = v match {
        case s: String => quoteIfNeeded(s)
        case other => other.toString
      }
      sb ++= s"$prefix$name: $encoded$lineSeparator"
    }
  }

  private def renderYamlBoolValue(
      sb: StringBuilder,
      prefix: String,
      name: String,
      value: Value[Boolean]
  ): Unit = {
    value.base.foreach { v =>
      sb ++= s"$prefix$name: $v$lineSeparator"
    }
  }

  private def renderYamlValues[A](
      sb: StringBuilder,
      prefix: String,
      name: String,
      values: Values[A],
      encode: A => String
  ): Unit = {
    if (values.base.nonEmpty) {
      val encoded = values.base.map(encode)
      sb ++= s"$prefix$name: [${encoded.mkString(", ")}]$lineSeparator"
    }
  }

  private def renderYamlStrings(
      sb: StringBuilder,
      prefix: String,
      name: String,
      values: Values[String]
  ): Unit = {
    if (values.base.nonEmpty) {
      val encoded = values.base.map(quoteIfNeeded)
      sb ++= s"$prefix$name: [${encoded.mkString(", ")}]$lineSeparator"
    }
  }

  private def renderYamlPaths(
      sb: StringBuilder,
      prefix: String,
      name: String,
      values: Values[os.RelPath]
  ): Unit = {
    if (values.base.nonEmpty) {
      val encoded = values.base.map(p => "./" + p.toString)
      sb ++= s"$prefix$name: [${encoded.mkString(", ")}]$lineSeparator"
    }
  }

  private def renderYamlSubPaths(
      sb: StringBuilder,
      prefix: String,
      name: String,
      values: Values[os.SubPath]
  ): Unit = {
    if (values.base.nonEmpty) {
      val encoded = values.base.map(p => if (p.segments.isEmpty) "." else "./" + p.toString)
      sb ++= s"$prefix$name: [${encoded.mkString(", ")}]$lineSeparator"
    }
  }

  private def renderYamlForkWorkingDir(
      sb: StringBuilder,
      prefix: String,
      value: Value[os.RelPath]
  ): Unit = {
    value.base.foreach { v =>
      // forkWorkingDir in YAML - we render relative paths
      if (v.ups == 0) {
        if (v.segments.isEmpty) {
          sb ++= s"${prefix}forkWorkingDir: .$lineSeparator"
        } else {
          sb ++= s"${prefix}forkWorkingDir: ./${v.toString}$lineSeparator"
        }
      }
      // For paths with ups, we skip (not representable simply in YAML)
    }
  }

  private def renderYamlPomParentProject(
      sb: StringBuilder,
      prefix: String,
      value: Value[Artifact]
  ): Unit = {
    value.base.foreach { v =>
      sb ++= s"${prefix}pomParentProject:$lineSeparator"
      sb ++= s"$prefix  group: ${quoteIfNeeded(v.group)}$lineSeparator"
      sb ++= s"$prefix  id: ${quoteIfNeeded(v.id)}$lineSeparator"
      sb ++= s"$prefix  version: ${quoteIfNeeded(v.version)}$lineSeparator"
    }
  }

  private def renderYamlPomSettings(
      sb: StringBuilder,
      prefix: String,
      value: Value[PomSettings]
  ): Unit = {
    value.base.foreach { pom =>
      sb ++= s"${prefix}pomSettings:$lineSeparator"
      if (pom.description.nonEmpty) {
        sb ++= s"$prefix  description: ${quoteIfNeeded(pom.description)}$lineSeparator"
      }
      if (pom.organization.nonEmpty) {
        sb ++= s"$prefix  organization: ${quoteIfNeeded(pom.organization)}$lineSeparator"
      }
      if (pom.url.nonEmpty) {
        sb ++= s"$prefix  url: ${quoteIfNeeded(pom.url)}$lineSeparator"
      }
      if (pom.licenses.nonEmpty) {
        // Simplified: just use license IDs
        val licenseIds = pom.licenses.collect {
          case l if l.id.nonEmpty => quoteIfNeeded(l.id)
          case l if l.name.nonEmpty => quoteIfNeeded(l.name)
        }
        if (licenseIds.nonEmpty) {
          sb ++= s"$prefix  licenses: [${licenseIds.mkString(", ")}]$lineSeparator"
        }
      }
      // Version control - simplified to URL if available
      val vc = pom.versionControl
      vc.browsableRepository.orElse(vc.connection).foreach { url =>
        sb ++= s"$prefix  versionControl: ${quoteIfNeeded(url)}$lineSeparator"
      }
      if (pom.developers.nonEmpty) {
        sb ++= s"$prefix  developers:$lineSeparator"
        for (dev <- pom.developers) {
          val attrs = Seq(
            if (dev.id.nonEmpty) Some(s""""id": "${dev.id}"""") else None,
            if (dev.name.nonEmpty) Some(s""""name": "${dev.name}"""") else None,
            if (dev.url.nonEmpty) Some(s""""url": "${dev.url}"""") else None,
            dev.organization.map(o => s""""organization": "$o""""),
            dev.organizationUrl.map(o => s""""organizationUrl": "$o"""")
          ).flatten
          if (attrs.nonEmpty) {
            sb ++= s"$prefix  - {${attrs.mkString(", ")}}$lineSeparator"
          }
        }
      }
    }
  }

  private def renderYamlVersionScheme(
      sb: StringBuilder,
      prefix: String,
      value: Value[String]
  ): Unit = {
    value.base.foreach { v =>
      sb ++= s"${prefix}versionScheme: $v$lineSeparator"
    }
  }

  private def renderYamlPublishProperties(
      sb: StringBuilder,
      prefix: String,
      values: Values[(String, String)]
  ): Unit = {
    if (values.base.nonEmpty) {
      sb ++= s"${prefix}publishProperties:$lineSeparator"
      for ((k, v) <- values.base) {
        sb ++= s"$prefix  ${quoteIfNeeded(k)}: ${quoteIfNeeded(v)}$lineSeparator"
      }
    }
  }

  private def encodeYamlModuleDep(dep: ModuleDep): String = {
    val base = dep.segments.mkString("/")
    val suffix = dep.childSegment.fold("")("/" + _)
    base + suffix
  }

  private def encodeYamlMvnDep(dep: MvnDep): String = {
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
    val classifierAttr = dep.classifier.collect {
      case c if c.nonEmpty => s";classifier=$c"
    }.getOrElse("")
    val typeAttr = dep.`type`.collect {
      case t if t.nonEmpty && t != "jar" => s";type=$t"
    }.getOrElse("")
    val excludeAttr = dep.excludes.map { case (org, name) => s";exclude=$org:$name" }.mkString

    s"${dep.organization}$binarySeparator${dep.name}$nameSuffix$platformSeparator${dep.version}$classifierAttr$typeAttr$excludeAttr"
  }

  private def encodeYamlOpt(opt: Opt): String = {
    // For YAML, we flatten the option group
    opt.group.map(quoteIfNeeded).mkString(", ")
  }

  private def quoteIfNeeded(s: String): String = {
    // Quote if contains special YAML characters or looks like a number/boolean
    if (s.isEmpty ||
      s.contains(":") ||
      s.contains("#") ||
      s.contains("[") ||
      s.contains("]") ||
      s.contains("{") ||
      s.contains("}") ||
      s.contains(",") ||
      s.contains("'") ||
      s.contains("\"") ||
      s.contains("\n") ||
      s.startsWith("-") ||
      s.startsWith("*") ||
      s.startsWith("&") ||
      s.startsWith("!") ||
      s.startsWith("|") ||
      s.startsWith(">") ||
      s.startsWith("%") ||
      s.startsWith("@") ||
      s.startsWith("`") ||
      s.matches("^(true|false|yes|no|on|off|null|~)$") ||
      s.matches("^[0-9].*") ||
      s.matches("^\\s.*") ||
      s.matches(".*\\s$")) {
      "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    } else {
      s
    }
  }

  // Keep these methods for backwards compatibility but they are no longer used for YAML output
  // They may still be called by the generators before the packages are passed to writeBuildFiles

  def withNamedDeps(packages: Seq[PackageSpec]): (Seq[(MvnDep, String)], Seq[PackageSpec]) = {
    // For YAML output, we don't use named deps - just return packages unchanged
    (Nil, packages)
  }

  def withBaseModule(
      packages: Seq[PackageSpec],
      testSupertype: String,
      moduleHierarchy: String*
  ): Option[(ModuleSpec, Seq[PackageSpec])] = {
    // For YAML output, we don't extract base modules - just return None
    None
  }
}
