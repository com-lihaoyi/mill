package mill.main.buildgen

import mainargs.{Flag, arg}
import mill.main.buildgen.BuildObject.Companions
import mill.main.client.CodeGenConstants.{
  buildFileExtensions,
  nestedBuildFileNames,
  rootBuildFileNames,
  rootModuleAlias
}
import mill.main.client.OutFiles
import mill.runner.FileImportGraph.backtickWrap

import scala.collection.immutable.SortedSet

@mill.api.internal
object BuildGenUtil {

  def renderIrTrait(value: IrTrait): String = {
    import value.*
    val zincWorker = jvmId.fold("") { jvmId =>
      val name = s"${baseModule}ZincWorker"
      val setting = renderZincWorker(name)
      val typedef = renderZincWorker(name, jvmId)

      s"""$setting
         |
         |$typedef""".stripMargin
    }

    s"""trait $baseModule ${renderExtends(moduleSupertypes)} {
       |
       |${renderJavacOptions(javacOptions)}
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
       |$zincWorker
       |}""".stripMargin

  }

  def renderIrPom(value: IrPom): String = {
    if (value == null) ""
    else {
      import value.*
      val mkLicenses = licenses.iterator.map(renderLicense).mkString("Seq(", ", ", ")")
      val mkDevelopers = developers.iterator.map(renderDeveloper).mkString("Seq(", ", ", ")")
      s"PomSettings(${escape(description)}, ${escape(organization)}, ${escape(url)}, $mkLicenses, ${renderVersionControl(versionControl)}, $mkDevelopers)"
    }
  }

  def renderIrBuild(value: IrBuild): String = {
    import value.*
    val testModuleTypedef =
      if (!hasTest) ""
      else {
        val declare =
          BuildGenUtil.renderTestModuleDecl(testModule, scopedDeps.testModule)

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
           |}""".stripMargin
      }

    s"""${renderArtifactName(projectName, dirs)}
       |
       |${renderJavacOptions(javacOptions)}
       |
       |${renderScalacOptions(scalacOptions)}
       |
       |${renderRepositories(repositories)}
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
       |${if (pomSettings == null) "" else renderPomSettings(renderIrPom(pomSettings))}
       |
       |${renderPublishVersion(publishVersion)}
       |
       |${renderPomPackaging(packaging)}
       |
       |${if (pomParentArtifact == null) ""
      else renderPomParentProject(renderArtifact(pomParentArtifact))}
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
    val name = if (dirs.isEmpty) rootBuildFileNames.head else nestedBuildFileNames.head
    os.sub / dirs / name
  }

  def renderImports(
      baseModule: Option[String],
      isNested: Boolean,
      packagesSize: Int
  ): SortedSet[String] = {
    scala.collection.immutable.SortedSet(
      "mill._",
      "mill.javalib._",
      "mill.javalib.publish._",
      "mill.scalalib.SbtModule"
    ) ++
      (if (isNested) baseModule.map(name => s"$$file.$name")
       else if (packagesSize > 1) Seq("$packages._")
       else None)
  }

  def buildFiles(workspace: os.Path): geny.Generator[os.Path] =
    os.walk.stream(workspace, skip = (workspace / OutFiles.out).equals)
      .filter(file => buildFileExtensions.contains(file.ext))

  def buildPackage(dirs: Seq[String]): String =
    (rootModuleAlias +: dirs).iterator.map(backtickWrap).mkString(".")

  def buildPackages[Module, Key](input: Tree[Node[Module]])(key: Module => Key)
      : Map[Key, String] =
    input.nodes()
      .map(node => (key(node.value), buildPackage(node.dirs)))
      .toSeq
      .toMap

  def renderBuildSource(node: Node[BuildObject]): os.Source = {
    val pkg = buildPackage(node.dirs)
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

  def compactBuildTree(tree: Tree[Node[BuildObject]]): Tree[Node[BuildObject]] = {
    println("compacting Mill build tree")

    def merge(parentCompanions: Companions, childCompanions: Companions): Companions = {
      var mergedParentCompanions = parentCompanions

      childCompanions.foreach { case entry @ (objectName, childConstants) =>
        val parentConstants = mergedParentCompanions.getOrElse(objectName, null)
        if (null == parentConstants) mergedParentCompanions += entry
        else {
          if (childConstants.exists { case (k, v) => v != parentConstants.getOrElse(k, v) })
            return null
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
      version: String = null,
      tpe: String = null,
      classifier: String = null,
      excludes: IterableOnce[(String, String)] = Seq.empty
  ): String = {
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

    s"ivy\"$group:$artifact$sepVersion$sepTpe$sepClassifier$sepExcludes\""
  }

  def isBom(groupArtifactVersion: (String, String, String)): Boolean =
    groupArtifactVersion._2.endsWith("-bom")

  def isNullOrEmpty(value: String): Boolean =
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

  def renderZincWorker(moduleName: String, jvmId: String): String =
    s"""object $moduleName extends ZincWorkerModule {
       |  def jvmId = "$jvmId"
       |}""".stripMargin

  def optional(construct: String, args: IterableOnce[String]): String =
    optional(construct + "(", args, ",", ")")

  def optional(start: String, args: IterableOnce[String], sep: String, end: String): String = {
    val itr = args.iterator
    if (itr.isEmpty) ""
    else itr.mkString(start, sep, end)
  }

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
    optional("def bomIvyDeps = super.bomIvyDeps() ++ Agg", args)

  def renderIvyDeps(args: IterableOnce[String]): String =
    optional("def ivyDeps = super.ivyDeps() ++ Agg", args)

  def renderModuleDeps(args: IterableOnce[String]): String =
    optional("def moduleDeps = super.moduleDeps ++ Seq", args)

  def renderCompileIvyDeps(args: IterableOnce[String]): String =
    optional("def compileIvyDeps = super.compileIvyDeps() ++ Agg", args)

  def renderCompileModuleDeps(args: IterableOnce[String]): String =
    optional("def compileModuleDeps = super.compileModuleDeps ++ Seq", args)

  def renderRunIvyDeps(args: IterableOnce[String]): String =
    optional("def runIvyDeps = super.runIvyDeps() ++ Agg", args)

  def renderRunModuleDeps(args: IterableOnce[String]): String =
    optional("def runModuleDeps = super.runModuleDeps ++ Seq", args)

  def renderJavacOptions(args: IterableOnce[String]): String =
    optional(
      "def javacOptions = super.javacOptions() ++ Seq",
      args.iterator.map(escape)
    )

  def renderScalacOptions(args: Option[IterableOnce[String]]): String =
    args.fold("")(args =>
      optional(
        "def scalacOptions = super.scalacOptions() ++ Seq",
        args.iterator.map(escape)
      )
    )

  def renderRepositories(args: IterableOnce[String]): String =
    optional(
      "def repositoriesTask = Task.Anon { super.repositoriesTask() ++ Seq(",
      args,
      ", ",
      ") }"
    )

  def renderResources(args: IterableOnce[os.SubPath]): String =
    optional(
      "def resources = Task.Sources { super.resources() ++ Seq(",
      args.iterator.map(sub => s"PathRef(millSourcePath / ${escape(sub.toString())})"),
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

  def renderPomSettings(arg: String): String =
    if (isNullOrEmpty(arg)) ""
    else s"def pomSettings = $arg"

  def renderPublishVersion(arg: String): String =
    if (isNullOrEmpty(arg)) ""
    else s"def publishVersion = ${escape(arg)}"

  def renderPublishProperties(args: IterableOnce[(String, String)]): String = {
    val tuples = args.iterator.map { case (k, v) => s"(${escape(k)}, ${escape(v)})" }
    optional("def publishProperties = super.publishProperties() ++ Map", tuples)
  }

  def renderZincWorker(moduleName: String): String =
    s"def zincWorker = mill.define.ModuleRef($moduleName)"

  val testModulesByGroup: Map[String, String] = Map(
    "junit" -> "TestModule.Junit4",
    "org.junit.jupiter" -> "TestModule.Junit5",
    "org.testng" -> "TestModule.TestNg",
    "org.scalatest" -> "TestModule.ScalaTest",
    "org.specs2" -> "TestModule.Specs2",
    "com.lihaoyi.utest" -> "TestModule.UTest",
    "org.scalameta" -> "TestModule.Munit",
    "com.disneystreaming" -> "Weaver",
    "dev.zio" -> "TestModule.ZioTest"
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

  def renderTestModuleDecl(testModule: String, testModuleType: Option[String]): String = {
    val name = backtickWrap(testModule)
    testModuleType match {
      case Some(supertype) => s"object $name extends MavenTests with $supertype"
      case None => s"trait $name extends MavenTests"
    }
  }

  @mainargs.main
  case class BasicConfig(
      @arg(doc = "name of generated base module trait defining shared settings", short = 'b')
      baseModule: Option[String] = None,
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
      @arg(
        doc = "distribution and version of custom JVM to configure in --base-module",
        short = 'j'
      )
      jvmId: Option[String] = None,
      @arg(doc = "capture Maven publish properties", short = 'p')
      publishProperties: Flag = Flag()
  )

  object Config {
    implicit def configParser: mainargs.ParserForClass[Config] = mainargs.ParserForClass[Config]
  }
}
