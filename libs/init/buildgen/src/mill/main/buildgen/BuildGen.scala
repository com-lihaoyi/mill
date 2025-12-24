package mill.main.buildgen

import mill.main.buildgen.ModuleSpec.*
import pprint.Util.literalize

import java.lang.System.lineSeparator

object BuildGen {

  def withNamedDeps(packages: Seq[PackageSpec]): (Seq[(MvnDep, String)], Seq[PackageSpec]) = {
    // For YAML output, we don't use named deps - just return packages unchanged
    (Nil, packages)
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
    // For test modules, don't extract common mvnDeps because YAML can't append to parent deps
    def parentTestModule(
        a: ModuleSpec,
        b: ModuleSpec
    ) = parentModule0(a, b, Seq(testSupertype)).copy(
      mandatoryMvnDeps = Values(),
      mvnDeps = Values(),
      compileMvnDeps = Values(),
      runMvnDeps = Values()
    )
    def parentModule(a: ModuleSpec, b: ModuleSpec) =
      parentModule0(a, b, moduleHierarchy.take(1)).copy(
        test = (a.test.toSeq ++ b.test.toSeq).reduceOption(parentTestModule)
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
      // Don't subtract nested test traits (MavenTests, SbtTests, etc.) - they can't be inherited
      // through a standalone trait and must remain in the child's extends clause.
      // Put nested traits first for proper linearization (MavenTests before ProjectBaseModuleTests)
      supertypes = {
        val filtered =
          (a.supertypes :+ parent.name).diff(parent.supertypes.filterNot(nestedTestTraits.contains))
        // Reorder: nested test traits first, then others
        val (nested, others) = filtered.partition(nestedTestTraits.contains)
        nested ++ others
      },
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
      val a0 =
        if (isChild(a))
          extendModule0(a.copy(test = a.test.zip(parent.test).map(extendModule0)), parent)
        else a
      a0.copy(children = a0.children.map(extendModule(_, parent)))
    }

    val childModules = packages.flatMap(_.module.tree).filter(isChild)
    Option.when(childModules.length > 1) {
      val baseModule = childModules.reduce(parentModule)
      val baseModule0 = baseModule.copy(
        name = "millbuild.ProjectBaseModule",
        // Use a top-level Tests trait instead of nested one for YAML compatibility
        test = baseModule.test.map(_.copy(name = "millbuild.ProjectBaseModuleTests"))
      )
      val packages0 = packages.map(pkg => pkg.copy(module = extendModule(pkg.module, baseModule0)))
      // Return the base module with simple name for file generation
      val baseModuleForFile = baseModule0.copy(
        name = "ProjectBaseModule",
        test = baseModule0.test.map(_.copy(name = "ProjectBaseModuleTests"))
      )
      (baseModuleForFile, packages0)
    }
  }

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

    // Write ProjectBaseModule.scala if we have a base module
    for (module <- baseModule) do {
      val file = os.sub / os.SubPath(s"mill-build/src/${module.name}.scala")
      println(s"writing $file")
      os.write(os.pwd / file, renderBaseModule(module), createFolders = true)
      // Also write a build.mill with "See Also" directive to trigger Mill to include mill-build/src/
      val buildMillFile = os.pwd / "build.mill"
      if (!os.exists(buildMillFile)) {
        println("writing build.mill")
        os.write(
          buildMillFile,
          s"""|/** See Also: build.mill.yaml */
              |/** See Also: mill-build/src/${module.name}.scala */
              |""".stripMargin
        )
      }
    }

    val rootPackage +: nestedPackages = packages0: @unchecked
    val millJvmOptsLine = if (millJvmOpts.isEmpty) ""
    else millJvmOpts.mkString("mill-jvm-opts: [\"", "\", \"", s"\"]$lineSeparator")
    println("writing build.mill.yaml")
    os.write(
      os.pwd / "build.mill.yaml",
      s"""mill-version: SNAPSHOT
         |mill-jvm-version: $millJvmVersion
         |$millJvmOptsLine${renderYamlPackage(rootPackage, isRootBuild = true)}
         |""".stripMargin
    )
    for (pkg <- nestedPackages) do {
      val file = os.sub / pkg.dir / "package.mill.yaml"
      println(s"writing $file")
      os.write(os.pwd / file, renderYamlPackage(pkg, isRootBuild = false))
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

  // ============================================
  // Scala rendering for ProjectBaseModule.scala
  // ============================================

  // These are nested traits that can't be extended by standalone traits
  private val nestedTestTraits = Set("MavenTests", "SbtTests", "JavaTests", "ScalaTests")

  private def renderBaseModule(module: ModuleSpec) = {
    import module.*
    val mainTrait = s"""trait $name ${renderScalaExtendsClause(supertypes ++ mixins)} {
                       |
                       |  ${renderScalaModuleBody(module)}
                       |}""".stripMargin
    // Generate Tests as a top-level trait for YAML compatibility
    // Filter out nested test traits (like MavenTests) that can't be extended by standalone traits
    val testTrait = test.fold("") { testSpec =>
      val standaloneSupertypes = testSpec.supertypes.filterNot(nestedTestTraits.contains)
      s"""
         |
         |trait ${testSpec.name} ${renderScalaExtendsClause(
          standaloneSupertypes ++ testSpec.mixins
        )} {
         |
         |  ${renderScalaModuleBody(testSpec)}
         |}""".stripMargin
    }
    s"""package millbuild
       |${renderScalaImports(module)}
       |$mainTrait$testTrait""".stripMargin
  }

  private def renderScalaImports(module: ModuleSpec) = {
    val imports = module.tree.flatMap(_.imports)
    ("import mill.*" +: imports).distinct.sorted.mkString(lineSeparator)
  }

  private def renderScalaExtendsClause(supertypes: Seq[String]) = {
    if (supertypes.isEmpty) "extends Module"
    else supertypes.mkString("extends ", ", ", "")
  }

  private def renderScalaModuleBody(module: ModuleSpec) = {
    import module.*
    s"""${renderScalaValues("moduleDeps", moduleDeps, encodeScalaModuleDep, isTask = false)}
       |
       |${renderScalaValues(
        "compileModuleDeps",
        compileModuleDeps,
        encodeScalaModuleDep,
        isTask = false
      )}
       |
       |${renderScalaValues("runModuleDeps", runModuleDeps, encodeScalaModuleDep, isTask = false)}
       |
       |${renderScalaValues("bomModuleDeps", bomModuleDeps, encodeScalaModuleDep, isTask = false)}
       |
       |${renderScalaValues("mandatoryMvnDeps", mandatoryMvnDeps, encodeScalaMvnDep)}
       |
       |${renderScalaValues("mvnDeps", mvnDeps, encodeScalaMvnDep)}
       |
       |${renderScalaValues("compileMvnDeps", compileMvnDeps, encodeScalaMvnDep)}
       |
       |${renderScalaValues("runMvnDeps", runMvnDeps, encodeScalaMvnDep)}
       |
       |${renderScalaValues("bomMvnDeps", bomMvnDeps, encodeScalaMvnDep)}
       |
       |${renderScalaValues("depManagement", depManagement, encodeScalaMvnDep)}
       |
       |${renderScala("scalaJSVersion", scalaJSVersion, encodeScalaString)}
       |
       |${renderScala("moduleKind", moduleKind, identity[String])}
       |
       |${renderScala("scalaNativeVersion", scalaNativeVersion, encodeScalaString)}
       |
       |${renderScala("scalaVersion", scalaVersion, encodeScalaString)}
       |
       |${renderScalaValues("scalacOptions", scalacOptions, encodeScalaLiteralOpt)}
       |
       |${renderScalaValues("scalacPluginMvnDeps", scalacPluginMvnDeps, encodeScalaMvnDep)}
       |
       |${renderScalaValues("javacOptions", javacOptions, encodeScalaOpt)}
       |
       |${renderScalaValues(
        "sourcesRootFolders",
        sourcesRootFolders,
        encodeScalaSubPath,
        isTask = false
      )}
       |
       |${renderScalaValues("sourcesFolders", sourcesFolders, encodeScalaSubPath, isTask = false)}
       |
       |${renderScalaSources("sources", sources)}
       |
       |${renderScalaSources("resources", resources)}
       |
       |${renderScalaValues("forkArgs", forkArgs, encodeScalaOpt)}
       |
       |${renderScala("forkWorkingDir", forkWorkingDir, encodeScalaRelPath("moduleDir", _))}
       |
       |${renderScalaValues("errorProneDeps", errorProneDeps, encodeScalaMvnDep)}
       |
       |${renderScalaValues("errorProneOptions", errorProneOptions, encodeScalaString)}
       |
       |${renderScalaValues(
        "errorProneJavacEnableOptions",
        errorProneJavacEnableOptions,
        encodeScalaOpt
      )}
       |
       |${renderScala("artifactName", artifactName, encodeScalaString)}
       |
       |${renderScala("pomPackagingType", pomPackagingType, encodeScalaString)}
       |
       |${renderScala("pomParentProject", pomParentProject, a => s"Some(${encodeScalaArtifact(a)})")}
       |
       |${renderScala("pomSettings", pomSettings, encodeScalaPomSettings)}
       |
       |${renderScala("publishVersion", publishVersion, encodeScalaString)}
       |
       |${renderScala("versionScheme", versionScheme, a => s"Some($a)")}
       |
       |${renderScalaValues(
        "publishProperties",
        publishProperties,
        encodeScalaProperty,
        collection = "Map"
      )}
       |
       |${renderScala("testParallelism", testParallelism, _.toString)}
       |
       |${renderScala("testSandboxWorkingDir", testSandboxWorkingDir, _.toString)}
       |
       |${renderScalaValues("repositories", repositories, encodeScalaString)}
       |""".stripMargin
  }

  private def renderScala[A](
      name: String,
      value: Value[A],
      encode: A => String,
      isTask: Boolean = true
  ) = {
    val defType = if (isTask) "def" else "override def"
    val cross = value.cross.map { (key, value) =>
      s"""case "$key" => ${encode(value)}"""
    }.mkString(lineSeparator)
    if (value.base.isDefined || cross.nonEmpty) {
      val base = value.base.map(encode).getOrElse("super." + name)
      val cases = if (cross.nonEmpty) {
        s"""|platformScalaBinaryVersion match {
            |  ${cross}
            |  case _ => $base
            |}""".stripMargin
      } else base
      s"$defType $name = $cases"
    } else ""
  }

  private def renderScalaValues[A](
      name: String,
      values: Values[A],
      encode: A => String,
      isTask: Boolean = true,
      collection: String = "Seq"
  ) = {
    val defType = if (isTask) "def" else "override def"
    val appender = if (values.appendSuper) s" ++ super.$name()" else ""
    val cross = values.cross.map { (key, values) =>
      s"""case "$key" => $collection(${values.map(encode).mkString(", ")})$appender"""
    }.mkString(lineSeparator)
    if (values.base.nonEmpty || cross.nonEmpty || values.appendRefs.nonEmpty) {
      val refs = values.appendRefs.map(ref => s" ++ $ref.$name").mkString
      val base = s"$collection(${values.base.map(encode).mkString(", ")})$appender$refs"
      val cases = if (cross.nonEmpty) {
        s"""|platformScalaBinaryVersion match {
            |  ${cross}
            |  case _ => $base
            |}""".stripMargin
      } else base
      s"$defType $name = $cases"
    } else ""
  }

  private def renderScalaSources(name: String, values: Values[os.RelPath]) = {
    if (values.base.nonEmpty) {
      val sources = values.base.map { source =>
        val ups = if (source.ups > 0) s".up($source.ups)" else ""
        if (source.segments.isEmpty) s"moduleDir$ups"
        else s"""moduleDir$ups / os.sub / "${source.segments.mkString("/")}""""
      }
      s"override def $name = super.$name() ++ Seq(${sources.map("PathRef(" + _ + ")").mkString(", ")})"
    } else ""
  }

  private def encodeScalaModuleDep(dep: ModuleDep): String = {
    val base = dep.segments.map(toScalaIdentifier).mkString(".")
    val suffix = dep.childSegment.fold("")("." + toScalaIdentifier(_))
    base + suffix
  }

  private def encodeScalaMvnDep(dep: MvnDep): String = {
    dep.ref.getOrElse {
      val binarySeparator = dep.cross match {
        case _: CrossVersion.Full => ":::"
        case _: CrossVersion.Binary => "::"
        case _ => ":"
      }
      val nameSuffix = dep.cross match {
        case v: CrossVersion.Constant => v.value
        case _ => ""
      }
      val platformSeparator =
        if (dep.version.isEmpty) "" else if (dep.cross.platformed) "::" else ":"
      val versionPart = dep.version
      val classifierAttr = dep.classifier.collect {
        case c if c.nonEmpty => s";classifier=$c"
      }.getOrElse("")
      val typeAttr = dep.`type`.collect {
        case t if t.nonEmpty && t != "jar" => s";type=$t"
      }.getOrElse("")
      val excludeAttr = dep.excludes.map { case (org, name) => s";exclude=$org:$name" }.mkString

      s"""mvn"${dep.organization}$binarySeparator${dep.name}$nameSuffix$platformSeparator$versionPart$classifierAttr$typeAttr$excludeAttr""""
    }
  }

  private def encodeScalaString(s: String) = literalize(s)

  private def encodeScalaOpt(opt: Opt) = opt.group.map(s => literalize(s)).mkString(", ")

  private def encodeScalaLiteralOpt(opt: Opt) = opt.group.mkString("Seq(", ", ", ")")

  private def encodeScalaSubPath(path: os.SubPath) =
    if (path.segments.isEmpty) "os.sub"
    else path.segments.map(s => literalize(s)).mkString("os.sub / ", " / ", "")

  private def encodeScalaRelPath(base: String, path: os.RelPath) = {
    val ups = if (path.ups > 0) s" / os.up".repeat(path.ups) else ""
    val downs = if (path.segments.isEmpty) ""
    else path.segments.map(s => literalize(s)).mkString(" / ", " / ", "")
    base + ups + downs
  }

  private def encodeScalaArtifact(artifact: Artifact) =
    s"""Artifact("${artifact.group}", "${artifact.id}", "${artifact.version}")"""

  private def encodeScalaPomSettings(pom: PomSettings) = {
    val description = literalize(pom.description)
    val organization = literalize(pom.organization)
    val url = literalize(pom.url)
    val licenses = pom.licenses.map(encodeScalaLicense).mkString("Seq(", ", ", ")")
    val versionControl = encodeScalaVersionControl(pom.versionControl)
    val developers = pom.developers.map(encodeScalaDeveloper).mkString("Seq(", ", ", ")")
    s"PomSettings($description, $organization, $url, $licenses, $versionControl, $developers)"
  }

  private def encodeScalaLicense(lic: License) = {
    val id = literalize(lic.id)
    val name = literalize(lic.name)
    val url = literalize(lic.url)
    val distribution = literalize(lic.distribution)
    s"License($id, $name, $url, isOsiApproved = ${lic.isOsiApproved}, isFsfLibre = ${lic.isFsfLibre}, $distribution)"
  }

  private def encodeScalaVersionControl(vc: VersionControl) = {
    val browsableRepository =
      vc.browsableRepository.map(s => literalize(s)).map("Some(" + _ + ")").getOrElse("None")
    val connection = vc.connection.map(s => literalize(s)).map("Some(" + _ + ")").getOrElse("None")
    val developerConnection =
      vc.developerConnection.map(s => literalize(s)).map("Some(" + _ + ")").getOrElse("None")
    val tag = vc.tag.map(s => literalize(s)).map("Some(" + _ + ")").getOrElse("None")
    s"VersionControl($browsableRepository, $connection, $developerConnection, $tag)"
  }

  private def encodeScalaDeveloper(dev: Developer) = {
    val id = literalize(dev.id)
    val name = literalize(dev.name)
    val url = literalize(dev.url)
    val organization =
      dev.organization.map(s => literalize(s)).map("Some(" + _ + ")").getOrElse("None")
    val organizationUrl =
      dev.organizationUrl.map(s => literalize(s)).map("Some(" + _ + ")").getOrElse("None")
    s"Developer($id, $name, $url, $organization, $organizationUrl)"
  }

  private def encodeScalaProperty(prop: (String, String)) =
    s"(${literalize(prop._1)}, ${literalize(prop._2)})"

  private val ScalaIdentifier = "^[a-zA-Z_][\\w]*$".r
  private def toScalaIdentifier(s: String) = if (ScalaIdentifier.matches(s)) s else s"`$s`"

  // ============================================
  // YAML rendering for build.mill.yaml and package.mill.yaml
  // ============================================

  private def renderYamlPackage(pkg: PackageSpec, isRootBuild: Boolean): String = {
    renderYamlModule(pkg.module, indent = 0, isRoot = isRootBuild)
  }

  private def renderYamlModule(module: ModuleSpec, indent: Int, isRoot: Boolean): String = {
    val sb = new StringBuilder
    val prefix = "  " * indent

    // Render extends (not for root module - root modules don't extend regular module traits)
    if (!isRoot) {
      val allSupertypes = module.supertypes ++ module.mixins
      if (allSupertypes.nonEmpty) {
        if (allSupertypes.size == 1) {
          sb ++= s"${prefix}extends: ${allSupertypes.head}$lineSeparator"
        } else {
          sb ++= s"${prefix}extends: [${allSupertypes.mkString(", ")}]$lineSeparator"
        }
      }
    }

    // Module-specific configuration (not for root module - it's not a JavaModule)
    if (!isRoot) {
      // Render module deps
      renderYamlModuleDeps(sb, prefix, "moduleDeps", module.moduleDeps)
      renderYamlModuleDeps(sb, prefix, "compileModuleDeps", module.compileModuleDeps)
      renderYamlModuleDeps(sb, prefix, "runModuleDeps", module.runModuleDeps)
      renderYamlModuleDeps(sb, prefix, "bomModuleDeps", module.bomModuleDeps)

      // Render maven deps
      renderYamlMvnDeps(
        sb,
        prefix,
        "mvnDeps",
        combineMvnDeps(module.mandatoryMvnDeps, module.mvnDeps)
      )
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
    }

    // Render additional module-specific configuration (not for root module)
    if (!isRoot) {
      // Render fork configuration
      renderYamlValues(sb, prefix, "forkArgs", module.forkArgs, encodeYamlOpt)
      renderYamlForkWorkingDir(sb, prefix, module.forkWorkingDir)

      // Render repositories
      renderYamlStrings(sb, prefix, "repositories", module.repositories)

      // Render error prone configuration
      renderYamlMvnDeps(sb, prefix, "errorProneDeps", module.errorProneDeps)
      renderYamlStrings(sb, prefix, "errorProneOptions", module.errorProneOptions)
      renderYamlValues(
        sb,
        prefix,
        "errorProneJavacEnableOptions",
        module.errorProneJavacEnableOptions,
        encodeYamlOpt
      )

      // Render publishing configuration only for modules that extend PublishModule
      val extendsPublishModule = module.supertypes.exists(s =>
        s.contains("PublishModule") || s.contains("ProjectBaseModule")
      )
      if (extendsPublishModule) {
        renderYamlValue(sb, prefix, "artifactName", module.artifactName)
        // Skip pomPackagingType for pom/war - these are aggregator/web modules in Maven
        // that don't need special packaging in Mill
        val skipPackagingType = module.pomPackagingType.base.exists(p => p == "pom" || p == "war")
        if (!skipPackagingType) {
          renderYamlValue(sb, prefix, "pomPackagingType", module.pomPackagingType)
        }
        renderYamlPomParentProject(sb, prefix, module.pomParentProject)
        renderYamlPomSettings(sb, prefix, module.pomSettings)
        renderYamlValue(sb, prefix, "publishVersion", module.publishVersion)
        renderYamlVersionScheme(sb, prefix, module.versionScheme)
        renderYamlPublishProperties(sb, prefix, module.publishProperties)
      }

      // Render test configuration
      renderYamlBoolValue(sb, prefix, "testParallelism", module.testParallelism)
      renderYamlBoolValue(sb, prefix, "testSandboxWorkingDir", module.testSandboxWorkingDir)
    }

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
    if (
      s.isEmpty ||
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
      s.matches(".*\\s$")
    ) {
      "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    } else {
      s
    }
  }
}
