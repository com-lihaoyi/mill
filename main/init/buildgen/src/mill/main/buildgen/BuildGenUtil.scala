package mill.main.buildgen

import geny.Generator
import mainargs.{Flag, arg}
import mill.constants.CodeGenConstants.{
  buildFileExtensions,
  nestedBuildFileNames,
  rootBuildFileNames,
  rootModuleAlias
}
import mill.constants.OutFiles
import mill.main.buildgen.BuildObject.Companions
import mill.runner.FileImportGraph.backtickWrap
import mill.scalalib.CrossVersion

import scala.collection.immutable.SortedSet
import scala.util.boundary

@mill.api.internal
object BuildGenUtil {

  def renderIrTrait(value: IrTrait): String = {
    import value.*
    val jvmWorker = jvmId.fold("") { jvmId =>
      val name = s"${baseModule}JvmWorker"
      val setting = renderJvmWorker(name)
      val typedef = renderJvmWorker(name, jvmId)

      s"""$setting
         |
         |$typedef""".stripMargin
    }

    s"""trait $baseModule ${renderExtends(moduleSupertypes)} {
       |
       |${renderJavacOptions(javacOptions)}
       |
       |${renderScalaVersion(scalaVersion)}
       |
       |${renderScalacOptions(scalacOptions)}
       |
       |${renderPomSettings(renderIrPom(pomSettings))}
       |
       |${renderPublishVersion(publishVersion)}
       |
       |${renderPublishProperties(publishProperties)}
       |
       |${renderRepositories(repositories)}
       |
       |$jvmWorker
       |}""".stripMargin

  }

  def renderIrPom(value: IrPom | Null): String = {
    if (value == null) ""
    else {
      import value.*
      val mkLicenses = licenses.iterator.map(renderLicense).mkString("Seq(", ", ", ")")
      val mkDevelopers = developers.iterator.map(renderDeveloper).mkString("Seq(", ", ", ")")
      s"PomSettings(${escape(description)}, ${escape(organization)}, ${escape(url)}, $mkLicenses, ${renderVersionControl(versionControl)}, $mkDevelopers)"
    }
  }

  /**
   * @param baseInfo to compare with [[build]] and render the values only if they are different.
   */
  def renderIrBuild(build: IrBuild, baseInfo: IrBaseInfo): String = {
    val baseTrait = baseInfo.moduleTypedef
    import build.*
    val testModuleTypedef =
      if (!hasTest) ""
      else {
        val declare =
          BuildGenUtil.renderTestModuleDecl(testModule, testModuleMainType, scopedDeps.testModule)

        // `testSandboxWorkingDir` is disabled as other build tools such as `sbt` don't run tests in the sandbox.
        s"""$declare {
           |
           |${renderBomIvyDeps(scopedDeps.testBomIvyDeps)}
           |
           |${renderIvyDeps(scopedDeps.testIvyDeps)}
           |
           |${renderModuleDeps(scopedDeps.testModuleDeps)}
           |
           |${renderCompileIvyDeps(scopedDeps.testCompileIvyDeps)}
           |
           |${renderCompileModuleDeps(scopedDeps.testCompileModuleDeps)}
           |
           |${renderResources(testResources)}
           |
           |def testSandboxWorkingDir = false
           |def testParallelism = false
           |}""".stripMargin
      }

    s"""${renderArtifactName(projectName, dirs)}
       |
       |${renderJavacOptions(
        javacOptions,
        if (baseTrait != null) baseTrait.javacOptions else Seq.empty
      )}
       |
       |${renderScalaVersion(scalaVersion, if (baseTrait != null) baseTrait.scalaVersion else None)}
       |
       |${renderScalacOptions(
        scalacOptions,
        if (baseTrait != null) baseTrait.scalacOptions else None
      )}
       |
       |${renderRepositories(
        repositories,
        if (baseTrait != null) baseTrait.repositories else Seq.empty
      )}
       |
       |${renderBomIvyDeps(scopedDeps.mainBomIvyDeps)}
       |
       |${renderIvyDeps(scopedDeps.mainIvyDeps)}
       |
       |${renderModuleDeps(scopedDeps.mainModuleDeps)}
       |
       |${renderCompileIvyDeps(scopedDeps.mainCompileIvyDeps)}
       |
       |${renderCompileModuleDeps(scopedDeps.mainCompileModuleDeps)}
       |
       |${renderRunIvyDeps(scopedDeps.mainRunIvyDeps)}
       |
       |${renderRunModuleDeps(scopedDeps.mainRunModuleDeps)}
       |
       |${
        if (pomSettings != (if (baseTrait != null) baseTrait.pomSettings else null))
          renderPomSettings(renderIrPom(pomSettings))
        else ""
      }
       |
       |${renderPublishVersion(
        publishVersion,
        if (baseTrait != null) baseTrait.publishVersion else null
      )}
       |
       |${renderPomPackaging(packaging)}
       |
       |${
        if (pomParentArtifact == null) ""
        else renderPomParentProject(renderArtifact(pomParentArtifact))
      }
       |
       |${renderPublishProperties(Nil)}
       |
       |${renderResources(resources)}
       |
       |${renderPublishProperties(publishProperties)}
       |
       |$testModuleTypedef""".stripMargin

  }
  def buildFile(dirs: Seq[String]): os.SubPath = {
    val name = if (dirs.isEmpty) rootBuildFileNames.get(0) else nestedBuildFileNames.get(0)
    os.sub / dirs / name
  }

  def renderImports(
      baseModule: Option[String],
      isNested: Boolean,
      packagesSize: Int,
      extraImports: Seq[String]
  ): SortedSet[String] = {
    scala.collection.immutable.SortedSet(
      "mill._",
      "mill.javalib._",
      "mill.javalib.publish._"
    ) ++
      extraImports ++
      (if (isNested) baseModule.map(name => s"_root_.build_.$name")
       else if (packagesSize > 1) Seq("$packages._")
       else None)
  }

  def buildFiles(workspace: os.Path): geny.Generator[os.Path] =
    os.walk.stream(workspace, skip = (workspace / OutFiles.out).equals)
      .filter(file => buildFileExtensions.contains(file.ext))

  def buildModuleFqn(dirs: Seq[String]): String =
    (rootModuleAlias +: dirs).iterator.map(backtickWrap).mkString(".")

  def buildModuleFqnMap[Module, Key](input: Generator[Node[Module]])(key: Module => Key)
      : Map[Key, String] =
    input
      .map(node => (key(node.value), buildModuleFqn(node.dirs)))
      .toSeq
      .toMap

  def renderBuildSource(node: Node[BuildObject]): os.Source = {
    val pkg = buildModuleFqn(node.dirs)
    val BuildObject(imports, companions, supertypes, inner, outer) = node.value
    val importStatements = imports.iterator.map("import " + _).mkString(linebreak)
    val companionTypedefs = companions.iterator.map {
      case (_, vals) if vals.isEmpty => ""
      case (name, vals) =>
        val members =
          vals.iterator.map { case (k, v) => s"val $k = $v" }.mkString(linebreak)

        s"""object $name {
           |
           |$members
           |}""".stripMargin
    }.mkString(linebreak2)

    s"""package $pkg
       |
       |$importStatements
       |
       |$companionTypedefs
       |
       |object `package` ${renderExtends(supertypes)} {
       |
       |$inner
       |}
       |
       |$outer
       |""".stripMargin
  }

  def compactBuildTree(tree: Tree[Node[BuildObject]]): Tree[Node[BuildObject]] = boundary {
    println("compacting Mill build tree")

    def merge(parentCompanions: Companions, childCompanions: Companions): Companions = {
      var mergedParentCompanions = parentCompanions

      childCompanions.foreach { case entry @ (objectName, childConstants) =>
        val parentConstants = mergedParentCompanions.getOrElse(objectName, null)
        if (null == parentConstants) mergedParentCompanions += entry
        else {
          if (childConstants.exists { case (k, v) => v != parentConstants.getOrElse(k, v) })
            boundary.break(null)
          else mergedParentCompanions += ((objectName, parentConstants ++ childConstants))
        }
      }

      mergedParentCompanions
    }

    tree.transform[Node[BuildObject]] { (node, children) =>
      var module = node.value
      val unmerged = Seq.newBuilder[Tree[Node[BuildObject]]]

      children.iterator.foreach {
        case child @ Tree(Node(_ :+ dir, nested), Seq()) if nested.outer.isEmpty =>
          val mergedCompanions = merge(module.companions, nested.companions)
          if (null == mergedCompanions) unmerged += child
          else {
            val mergedImports = module.imports ++ nested.imports
            val mergedInner = {
              val name = backtickWrap(dir)
              val supertypes = nested.supertypes.filterNot(_ == "RootModule")

              s"""${module.inner}
                 |
                 |object $name ${renderExtends(supertypes)}  {
                 |
                 |${nested.inner}
                 |}""".stripMargin
            }

            module = module.copy(
              imports = mergedImports,
              companions = mergedCompanions,
              inner = mergedInner
            )
          }
        case child => unmerged += child
      }

      val unmergedChildren = unmerged.result()
      if (node.dirs.isEmpty) {
        module = module.copy(imports = module.imports.filterNot(_.startsWith("$file")))
        if (unmergedChildren.isEmpty) {
          module = module.copy(imports = module.imports.filterNot(_ == "$packages._"))
        }
      }

      Tree(node.copy(value = module), unmergedChildren)
    }
  }

  def escape(value: String): String =
    pprint.Util.literalize(if (value == null) "" else value)

  def escapeOption(value: String): String =
    if (null == value) "None" else s"Some(\"$value\")"

  def renderIvyString(
      group: String,
      artifact: String,
      crossVersion: Option[CrossVersion] = None,
      version: String | Null = null,
      tpe: String | Null = null,
      classifier: String | Null = null,
      excludes: IterableOnce[(String, String)] = Seq.empty
  ): String = {
    val sepArtifact = crossVersion match {
      case None => s":$artifact"
      case Some(value) => value match {
          case CrossVersion.Constant(value, _) => s":${artifact}_$value"
          case CrossVersion.Binary(_) => s"::$artifact"
          case CrossVersion.Full(_) => s":::$artifact"
        }
    }
    val sepVersion =
      if (null == version) {
        println(
          s"assuming $group:$artifact is a BOM dependency; if not, please specify version in the generated build file"
        )
        ""
      } else s":$version"
    val sepTpe = tpe match {
      case null | "" | "jar" => "" // skip default
      case tpe => s";type=$tpe"
    }
    val sepClassifier = classifier match {
      case null | "" => ""
      case s"$${$v}" => // drop values like ${os.detected.classifier}
        println(s"dropping classifier $${$v} for dependency $group:$artifact:$version")
        ""
      case classifier => s";classifier=$classifier"
    }
    val sepExcludes = excludes.iterator
      .map { case (group, artifact) => s";exclude=$group:$artifact" }
      .mkString

    s"ivy\"$group$sepArtifact$sepVersion$sepTpe$sepClassifier$sepExcludes\""
  }

  def isBom(groupArtifactVersion: (String, String, String)): Boolean =
    groupArtifactVersion._2.endsWith("-bom")

  def isNullOrEmpty(value: String | Null): Boolean =
    null == value || value.isEmpty

  val linebreak: String =
    """
      |""".stripMargin

  val linebreak2: String =
    """
      |
      |""".stripMargin

  val mavenMainResourceDir: os.SubPath =
    os.sub / "src/main/resources"

  val mavenTestResourceDir: os.SubPath =
    os.sub / "src/test/resources"

  def renderArtifact(artifact: IrArtifact): String =
    s"Artifact(${escape(artifact.group)}, ${escape(artifact.id)}, ${escape(artifact.version)})"

  def renderDeveloper(dev: IrDeveloper): String = {
    s"Developer(${escape(dev.id)}, ${escape(dev.name)}, ${escape(dev.url)}, ${escapeOption(dev.organization)}, ${escapeOption(dev.organizationUrl)})"
  }

  def renderExtends(supertypes: Seq[String]): String = supertypes match {
    case Seq() => ""
    case Seq(head) => s"extends $head"
    case head +: tail => tail.mkString(s"extends $head with ", " with ", "")
  }

  def renderLicense(
      license: IrLicense
  ): String =
    s"License(${escape(license.id)}, ${escape(license.name)}, ${escape(license.url)}, ${license.isOsiApproved}, ${license.isFsfLibre}, ${escape(license.distribution)})"

  def renderVersionControl(vc: IrVersionControl): String =
    s"VersionControl(${escapeOption(vc.url)}, ${escapeOption(vc.connection)}, ${escapeOption(vc.devConnection)}, ${escapeOption(vc.tag)})"

  def renderJvmWorker(moduleName: String, jvmId: String): String =
    s"""object $moduleName extends JvmWorkerModule {
       |  def jvmId = "$jvmId"
       |}""".stripMargin

  // TODO consider renaming to `renderOptionalDef` or `renderIfArgsNonEmpty`?
  def optional(construct: String, args: IterableOnce[String]): String =
    optional(construct + "(", args, ",", ")")

  def optional(start: String, args: IterableOnce[String], sep: String, end: String): String = {
    val itr = args.iterator
    if (itr.isEmpty) ""
    else itr.mkString(start, sep, end)
  }

  def renderSeqWithSuper(
      defName: String,
      args: Seq[String],
      superArgs: Seq[String] = Seq.empty,
      elementType: String,
      transform: String => String
  ): Option[String] =
    if (args.startsWith(superArgs)) {
      val superLength = superArgs.length
      if (args.length == superLength) None
      else
        // Note that the super def is called even when it's empty.
        // Some super functions can be called without parentheses, but we just add them here for simplicity.
        Some(args.iterator.drop(superLength).map(transform)
          .mkString(s"super.$defName() ++ Seq(", ",", ")"))
    } else
      Some(
        if (args.isEmpty)
          s"Seq.empty[$elementType]" // The inferred type is `Seq[Nothing]` otherwise.
        else args.iterator.map(transform).mkString("Seq(", ",", ")")
      )

  def renderSeqTargetDefWithSuper(
      defName: String,
      args: Seq[String],
      superArgs: Seq[String] = Seq.empty,
      elementType: String,
      transform: String => String
  ) =
    renderSeqWithSuper(defName, args, superArgs, elementType, transform).map(s"def $defName = " + _)

  def renderSeqTaskDefWithSuper(
      defName: String,
      args: Seq[String],
      superArgs: Seq[String] = Seq.empty,
      elementType: String,
      transform: String => String
  ) =
    renderSeqWithSuper(defName, args, superArgs, elementType, transform).map(s =>
      s"def $defName = Task.Anon { $s }"
    )

  def scalafmtConfigFile: os.Path =
    os.temp(
      """version = "3.8.4"
        |runner.dialect = scala213
        |newlines.source=fold
        |newlines.topLevelStatementBlankLines = [
        |  {
        |    blanks { before = 1 }
        |  }
        |]
        |""".stripMargin
    )

  def renderArtifactName(name: String, dirs: Seq[String]): String =
    if (dirs.nonEmpty && dirs.last == name) "" // skip default
    else s"def artifactName = ${escape(name)}"

  def renderBomIvyDeps(args: IterableOnce[String]): String =
    optional("def bomIvyDeps = super.bomIvyDeps() ++ Seq", args)

  def renderIvyDeps(args: IterableOnce[String]): String =
    optional("def ivyDeps = super.ivyDeps() ++ Seq", args)

  def renderModuleDeps(args: IterableOnce[String]): String =
    optional("def moduleDeps = super.moduleDeps ++ Seq", args)

  def renderCompileIvyDeps(args: IterableOnce[String]): String =
    optional("def compileIvyDeps = super.compileIvyDeps() ++ Seq", args)

  def renderCompileModuleDeps(args: IterableOnce[String]): String =
    optional("def compileModuleDeps = super.compileModuleDeps ++ Seq", args)

  def renderRunIvyDeps(args: IterableOnce[String]): String =
    optional("def runIvyDeps = super.runIvyDeps() ++ Seq", args)

  def renderRunModuleDeps(args: IterableOnce[String]): String =
    optional("def runModuleDeps = super.runModuleDeps ++ Seq", args)

  def renderJavacOptions(args: Seq[String], superArgs: Seq[String] = Seq.empty): String =
    renderSeqTargetDefWithSuper("javacOptions", args, superArgs, "String", escape).getOrElse("")

  def renderScalaVersion(arg: Option[String], superArg: Option[String] = None): String =
    if (arg != superArg) arg.fold("")(scalaVersion => s"def scalaVersion = ${escape(scalaVersion)}")
    else ""

  def renderScalacOptions(
      args: Option[Seq[String]],
      superArgs: Option[Seq[String]] = None
  ): String =
    renderSeqTargetDefWithSuper(
      "scalacOptions",
      args.getOrElse(Seq.empty),
      superArgs.getOrElse(Seq.empty),
      "String",
      escape
    ).getOrElse("")

  def renderRepositories(args: Seq[String], superArgs: Seq[String] = Seq.empty): String =
    renderSeqTaskDefWithSuper(
      "repositoriesTask",
      args,
      superArgs,
      "coursier.Repository",
      identity
    ).getOrElse("")

  def renderResources(args: IterableOnce[os.SubPath]): String =
    optional(
      "def resources = Task.Sources { super.resources() ++ Seq(",
      args.iterator.map(sub => s"PathRef(moduleDir / ${escape(sub.toString())})"),
      ", ",
      ") }"
    )

  def renderPomPackaging(packaging: String): String =
    if (isNullOrEmpty(packaging) || "jar" == packaging) "" // skip default
    else {
      val pkg = if ("pom" == packaging) "PackagingType.Pom" else escape(packaging)
      s"def pomPackagingType = $pkg"
    }

  def renderPomParentProject(artifact: String): String =
    if (isNullOrEmpty(artifact)) ""
    else s"def pomParentProject = Some($artifact)"

  def renderPomSettings(arg: String | Null, superArg: String | Null = null): String =
    if (isNullOrEmpty(arg)) ""
    else s"def pomSettings = $arg"

  def renderPublishVersion(arg: String | Null, superArg: String | Null = null): String =
    if (arg != superArg)
      if (isNullOrEmpty(arg)) ""
      else s"def publishVersion = ${escape(arg)}"
    else ""

  def renderPublishProperties(
      args: Seq[(String, String)],
      superArgs: Seq[(String, String)] = Seq.empty
  ): String = {
    val tuples = args.iterator.map { case (k, v) => s"(${escape(k)}, ${escape(v)})" }
    optional("def publishProperties = super.publishProperties() ++ Map", tuples)
  }

  def renderJvmWorker(moduleName: String): String =
    s"def jvmWorker = mill.define.ModuleRef($moduleName)"

  val testModulesByGroup: Map[String, String] = Map(
    "junit" -> "TestModule.Junit4",
    "org.junit.jupiter" -> "TestModule.Junit5",
    "org.testng" -> "TestModule.TestNg",
    "org.scalatest" -> "TestModule.ScalaTest",
    "org.specs2" -> "TestModule.Specs2",
    "com.lihaoyi" -> "TestModule.UTest",
    "org.scalameta" -> "TestModule.Munit",
    "com.disneystreaming" -> "TestModule.Weaver",
    "dev.zio" -> "TestModule.ZioTest",
    "org.scalacheck" -> "TestModule.ScalaCheck"
  )

  def writeBuildObject(tree: Tree[Node[BuildObject]]): Unit = {
    val nodes = tree.nodes().toSeq
    println(s"generated ${nodes.length} Mill build file(s)")

    println("removing existing Mill build files")
    val workspace = os.pwd
    buildFiles(workspace).foreach(os.remove.apply)

    nodes.foreach { node =>
      val file = buildFile(node.dirs)
      val source = renderBuildSource(node)
      println(s"writing Mill build file to $file")
      os.write(workspace / file, source)
    }
  }

  def renderTestModuleDecl(
      testModule: String,
      testModuleMainType: String,
      testModuleExtraType: Option[String]
  ): String = {
    val name = backtickWrap(testModule)
    testModuleExtraType match {
      case Some(supertype) => s"object $name extends $testModuleMainType with $supertype"
      case None => s"trait $name extends $testModuleMainType"
    }
  }

  @mainargs.main
  case class BasicConfig(
      @arg(doc = "name of generated base module trait defining shared settings", short = 'b')
      baseModule: Option[String] = None,
      @arg(
        doc = "distribution and version of custom JVM to configure in --base-module",
        short = 'j'
      )
      jvmId: Option[String] = None,
      @arg(doc = "name of generated nested test module", short = 't')
      testModule: String = "test",
      @arg(doc = "name of generated companion object defining dependency constants", short = 'd')
      depsObject: Option[String] = None,
      @arg(doc = "merge build files generated for a multi-module build", short = 'm')
      merge: Flag = Flag()
  )
  object BasicConfig {
    implicit def parser: mainargs.ParserForClass[BasicConfig] = mainargs.ParserForClass[BasicConfig]
  }
  // TODO alternative names: `MavenAndGradleConfig`, `MavenAndGradleSharedConfig`
  @mainargs.main
  case class Config(
      basicConfig: BasicConfig,
      @arg(doc = "capture Maven publish properties", short = 'p')
      publishProperties: Flag = Flag()
  )

  object Config {
    implicit def configParser: mainargs.ParserForClass[Config] = mainargs.ParserForClass[Config]
  }
}
