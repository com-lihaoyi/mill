package mill.main.buildgen

import mill.constants.CodeGenConstants.rootModuleAlias
import mill.constants.OutFiles.millBuild
import mill.init.Util.buildFiles
import mill.internal.Util.backtickWrap
import mill.main.buildgen.ModuleConfig.*
import pprint.Util.literalize

import scala.collection.mutable
import scala.math.Ordered.orderingToOrdered
import scala.reflect.TypeTest

class BuildWriter(build: BuildSpec, renderCrossValueInTask: String = "crossValue") {

  private val mvnDepRef: mutable.Map[MvnDep, String] = mutable.Map.empty

  def writeFiles(): Unit = {
    val toRemove = buildFiles(os.pwd)
    if (toRemove.nonEmpty) {
      println("removing existing Mill build files")
      toRemove.foreach(os.remove.apply)
    }

    import build.*
    val rootPackage +: nestedPackages = packages.toSeq: @unchecked
    val rootBuildFile = os.sub / "build.mill"
    println(s"writing Mill build file to $rootBuildFile")
    os.write(os.pwd / rootBuildFile, renderRootPackage(rootPackage))
    nestedPackages.foreach { pkg =>
      val buildFile = os.sub / pkg.segments / "package.mill"
      println(s"writing Mill build file to $buildFile")
      os.write(os.pwd / buildFile, renderPackage(pkg))
    }

    metaBuild.foreach { meta =>
      val rootDir = os.sub / millBuild
      val srcDir = rootDir / "src"
      os.makeDir.all(os.pwd / srcDir)
      import meta.*
      baseModules.foreach { baseModule =>
        val srcFile = srcDir / s"${baseModule.name}.scala"
        println(s"writing Mill meta-build file to $srcFile")
        os.write(os.pwd / srcFile, renderBaseModule(rootModuleName, baseModule))
      }
      val depsSrcFile = srcDir / s"$depsObjectName.scala"
      println(s"writing Mill meta-build file to $depsSrcFile")
      os.write(os.pwd / depsSrcFile, renderDepsObject(rootModuleName, depsObjectName))
    }

    println(
      s"NOTE: It is recommended to set `mill-jvm-version` in the header section of the root $rootBuildFile file."
    )
  }

  def renderRootPackage(pkg: PackageSpec) = {
    import build.*
    val header = Seq("mill-version: " + millVersion) ++
      Option.when(millJvmOpts.nonEmpty) {
        "mill-jvm-opts: " + millJvmOpts.map(literalize(_)).mkString("[", ", ", "]")
      }
    s"""${renderLines(header.map("//| " + _))}
       |${renderPackage(pkg)}
       |""".stripMargin
  }

  def renderPackage(pkg: PackageSpec) = {
    import pkg.*
    s"""package ${(rootModuleAlias +: segments.map(backtickWrap)).mkString(".")}
       |
       |${renderModuleImports(module)}
       |
       |${renderModule(module, isPackageRoot = true)}
       |""".stripMargin
  }

  def renderBaseModule(packageName: String, baseModule: ModuleSpec) = {
    import baseModule.*
    s"""package $packageName
       |
       |${renderBaseModuleImports(baseModule)}
       |
       |trait $name ${renderExtendsClause(supertypes ++ mixins)} {
       |
       |  ${renderModuleConfigs(configs, crossConfigs)}
       |}""".stripMargin
  }

  def renderDepsObject(packageName: String, name: String) = {
    s"""package $packageName
       |
       |import mill.javalib._
       |
       |object $name {
       |
       |${renderLines(mvnDepRef.toSeq.sortBy(_._2).map((dep, ref) =>
        s"val ${backtickWrap(ref)} = $dep"
      ))}
       |}""".stripMargin
  }

  def renderBaseModuleImports(baseModule: ModuleSpec) = {
    val wildcards = mutable.SortedSet.empty[String]
    import baseModule.*
    configs.foreach(addImports(wildcards, _))
    renderLines(wildcards.map(s => s"import $s._"))
  }

  def renderModuleImports(module: ModuleSpec) = {
    val wildcards = mutable.SortedSet.empty[String]
    for spec <- module.sequence do {
      import spec.*
      if (supertypes.isEmpty || crossConfigs.nonEmpty) wildcards += "mill"
      if (configs.nonEmpty) wildcards ++= build.metaBuild.map(_.rootModuleName)
      configs.foreach(addImports(wildcards, _))
    }
    renderLines(wildcards.map(s => s"import $s._"))
  }

  private def addImports(wildcards: mutable.SortedSet[String], config: ModuleConfig) =
    config match {
      case _: PublishModule => wildcards += "mill.javalib" += "mill.javalib.publish"
      case _: ErrorProneModule => wildcards += "mill.javalib" += "mill.javalib.errorprone"
      case _: ScalaModule => wildcards += "mill.scalalib"
      case _: ScalaJSModule =>
        wildcards += "mill.scalalib" += "mill.scalajslib" += "mill.scalajslib.api"
      case _: ScalaNativeModule => wildcards += "mill.scalalib" += "mill.scalanativelib"
      case _: SbtPlatformModule => wildcards += "mill.scalalib"
      case _ => wildcards += "mill.javalib"
    }

  def renderModule(module: ModuleSpec, isPackageRoot: Boolean = false): String = {
    import module.*
    s"""${renderModuleDeclaration(module, isPackageRoot)} {
       |
       |  ${renderModuleConfigs(configs, crossConfigs)}
       |
       |  ${renderBlocks(nestedModules.sortBy(_.name).map(renderModule(_))*)}
       |}""".stripMargin
  }

  def renderModuleDeclaration(module: ModuleSpec, isPackageRoot: Boolean) = {
    import module.*
    val objectName = backtickWrap(if (isPackageRoot) "package" else name)
    if (crossConfigs.isEmpty || isTestModule) {
      val objectExtends = renderExtendsClause(supertypes ++ mixins)
      s"object $objectName $objectExtends"
    } else {
      val traitName = name.split("\\W") match {
        case Array("") => backtickWrap(name + "Module")
        case parts => parts.map(_.capitalize).mkString("", "", "Module")
      }
      val crossValues = crossConfigs.map((v, _) => literalize(v)).sorted
      val objectExtends = s"extends Cross[$traitName](${crossValues.mkString(", ")})"
      val traitExtends = renderExtendsClause(supertypes ++ mixins)
      s"""object $objectName $objectExtends
         |trait $traitName $traitExtends""".stripMargin
    }
  }

  def renderExtendsClause(supertypes: Seq[String]) = supertypes match {
    case Nil => "extends Module"
    case head +: tail => s" extends $head" + tail.map(" with " + _).mkString
  }

  def renderModuleConfigs(
      configs: Seq[ModuleConfig],
      crossConfigs: Seq[(String, Seq[ModuleConfig])]
  ) = {
    def crossConfig[T <: ModuleConfig](using T: TypeTest[ModuleConfig, T]) =
      crossConfigs.flatMap((k, xs) =>
        xs.collectFirst {
          case t: T => (k, t)
        }
      )
    renderLines(configs.map {
      case config: CoursierModule => renderCoursierModule(config, crossConfig)
      case config: JavaHomeModule => renderJavaHomeModule(config, crossConfig)
      case config: RunModule => renderRunModule(config, crossConfig)
      case config: JavaModule => renderJavaModule(config, crossConfig)
      case config: PublishModule => renderPublishModule(config, crossConfig)
      case config: ErrorProneModule => renderErrorProneModule(config, crossConfig)
      case config: ScalaModule => renderScalaModule(config, crossConfig)
      case config: ScalaJSModule => renderScalaJSModule(config, crossConfig)
      case config: ScalaNativeModule => renderScalaNativeModule(config, crossConfig)
      case config: SbtPlatformModule => renderSbtPlatformModule(config, crossConfig)
      case config: TestModule => renderTestModule(config, crossConfig)
    })
  }

  def renderCoursierModule(config: CoursierModule, crossConfigs: Seq[(String, CoursierModule)]) = {
    renderBlocks(renderTaskAsSeq(
      "repositories",
      config.repositories,
      crossConfigs.map((k, v) => (k, v.repositories)),
      literalize(_)
    ))
  }

  def renderJavaHomeModule(config: JavaHomeModule, crossConfigs: Seq[(String, JavaHomeModule)]) = {
    renderBlocks(renderTask(
      "jvmId",
      config.jvmId,
      crossConfigs.map((k, v) => (k, v.jvmId)),
      literalize(_)
    ))
  }

  def renderRunModule(config: RunModule, crossConfigs: Seq[(String, RunModule)]) = {
    renderBlocks(renderTask(
      "forkWorkingDir",
      config.forkWorkingDir,
      crossConfigs.map((k, v) => (k, v.forkWorkingDir)),
      identity
    ))
  }

  def renderJavaModule(config: JavaModule, crossConfigs: Seq[(String, JavaModule)]) = {
    def cross[A](f: JavaModule => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    renderBlocks(
      renderTaskAsSeq("mvnDeps", mvnDeps, cross(_.mvnDeps), renderMvnDep),
      renderTaskAsSeq("compileMvnDeps", compileMvnDeps, cross(_.compileMvnDeps), renderMvnDep),
      renderTaskAsSeq("runMvnDeps", runMvnDeps, cross(_.runMvnDeps), renderMvnDep),
      renderTaskAsSeq("bomMvnDeps", bomMvnDeps, cross(_.bomMvnDeps), renderMvnDep),
      renderMemberAsSeq("moduleDeps", moduleDeps, cross(_.moduleDeps), renderModuleDep),
      renderMemberAsSeq(
        "compileModuleDeps",
        compileModuleDeps,
        cross(_.compileModuleDeps),
        renderModuleDep
      ),
      renderMemberAsSeq("runModuleDeps", runModuleDeps, cross(_.runModuleDeps), renderModuleDep),
      renderTaskAsSeq("javacOptions", javacOptions, cross(_.javacOptions), literalize(_)),
      renderTask("artifactName", artifactName, cross(_.artifactName), literalize(_))
    )
  }

  def renderPublishModule(config: PublishModule, crossConfigs: Seq[(String, PublishModule)]) = {
    def cross[A](f: PublishModule => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    renderBlocks(
      renderMember("pomPackagingType", pomPackagingType, cross(_.pomPackagingType), literalize(_)),
      renderTask(
        "pomParentProject",
        pomParentProject,
        cross(_.pomParentProject),
        v => s"Some(${renderArtifact(v)})"
      ),
      renderTask("pomSettings", pomSettings, cross(_.pomSettings), renderPomSettings),
      renderTask("publishVersion", publishVersion, cross(_.publishVersion), literalize(_)),
      renderTask(
        "versionScheme",
        versionScheme,
        cross(_.versionScheme),
        v => s"Some(${renderVersionScheme(v)})"
      ),
      renderTaskAsMap(
        "publishProperties",
        publishProperties,
        cross(_.publishProperties),
        literalize(_),
        literalize(_)
      )
    )
  }

  def renderErrorProneModule(
      config: ErrorProneModule,
      crossConfigs: Seq[(String, ErrorProneModule)]
  ) = {
    def cross[A](f: ErrorProneModule => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    renderBlocks(
      renderTask("errorProneVersion", errorProneVersion, cross(_.errorProneVersion), literalize(_)),
      renderTaskAsSeq(
        "errorProneOptions",
        errorProneOptions,
        cross(_.errorProneOptions),
        literalize(_)
      ),
      renderTaskAsSeq(
        "errorProneJavacEnableOptions",
        errorProneJavacEnableOptions,
        cross(_.errorProneJavacEnableOptions),
        literalize(_)
      ),
      renderTaskAsSeq("errorProneDeps", errorProneDeps, cross(_.errorProneDeps), renderMvnDep)
    )
  }

  def renderScalaModule(config: ScalaModule, crossConfigs: Seq[(String, ScalaModule)]) = {
    def cross[A](f: ScalaModule => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    renderBlocks(
      renderTask("scalaVersion", scalaVersion, cross(_.scalaVersion), literalize(_)),
      renderTaskAsSeq("scalacOptions", scalacOptions, cross(_.scalacOptions), literalize(_)),
      renderTaskAsSeq(
        "scalacPluginMvnDeps",
        scalacPluginMvnDeps,
        cross(_.scalacPluginMvnDeps),
        renderMvnDep
      )
    )
  }

  def renderScalaJSModule(config: ScalaJSModule, crossConfigs: Seq[(String, ScalaJSModule)]) = {
    def cross[A](f: ScalaJSModule => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    renderBlocks(
      renderTask("scalaJSVersion", scalaJSVersion, cross(_.scalaJSVersion), literalize(_)),
      renderTask("moduleKind", moduleKind, cross(_.moduleKind), "ModuleKind." + _)
    )
  }

  def renderScalaNativeModule(
      config: ScalaNativeModule,
      crossConfigs: Seq[(String, ScalaNativeModule)]
  ) = {
    renderBlocks(renderTask(
      "scalaNativeVersion",
      config.scalaNativeVersion,
      crossConfigs.map((k, v) => (k, v.scalaNativeVersion)),
      literalize(_)
    ))
  }

  def renderSbtPlatformModule(
      config: SbtPlatformModule,
      crossConfigs: Seq[(String, SbtPlatformModule)]
  ) = {
    renderBlocks(renderMemberAsSeq(
      "sourcesRootFolders",
      config.sourcesRootFolders,
      crossConfigs.map((k, v) => (k, v.sourcesRootFolders)),
      s => s"os.sub / ${literalize(s)}"
    ))
  }

  def renderTestModule(
      config: TestModule,
      crossConfigs: Seq[(String, TestModule)]
  ) = {
    def cross[A](f: TestModule => A) = crossConfigs.map((k, v) => (k, f(v)))
    import config.*
    renderBlocks(
      renderTask("testParallelism", testParallelism, cross(_.testParallelism), identity),
      renderTask(
        "testSandboxWorkingDir",
        testSandboxWorkingDir,
        cross(_.testSandboxWorkingDir),
        identity
      )
    )
  }

  def renderMvnDep(dep: MvnDep) = build.metaBuild.fold(dep.toString()) { meta =>
    var ref = mvnDepRef.getOrElse(dep, null)
    if (ref == null) {
      ref = dep.name.split("\\W") match
        case Array(head) => head
        case parts => parts.tail.map(_.capitalize).mkString(parts.head, "", "")
      if (mvnDepRef.valuesIterator.contains(ref)) {
        // generate name for "duplicate" that will stand out visually, eg:
        //    val catsCore = mvn"org.typelevel::cats-core:2.0.0"
        //    val `catsCore#0` = mvn"org.typelevel::cats-core:2.6.1"
        ref += "#"
        ref += mvnDepRef.valuesIterator.count(_.startsWith(ref))
      }
      mvnDepRef.put(dep, ref)
    }
    meta.depsObjectName + "." + backtickWrap(ref)
  }

  def renderModuleDep(dep: ModuleDep) = {
    import dep.*
    val segments0 = rootModuleAlias +: segments.map(backtickWrap)
    def segment(i: Int) = segments0(i) + crossArgs.get(i - 1).fold("")(_.mkString("(", ", ", ")"))
    segments0.indices.map(segment).mkString(".")
  }

  def renderArtifact(value: Artifact) = {
    import value.*
    s"Artifact(${literalize(group)}, ${literalize(id)}, ${literalize(version)})"
  }

  def renderPomSettings(value: PomSettings) = {
    import value.*
    // TODO Supporting optional tags requires changes in Mill.
    val description0 = literalize(description.getOrElse(""))
    val organization0 = literalize(organization.getOrElse(""))
    val url0 = literalize(url.getOrElse(""))
    val licenses0 = s"Seq(${licenses.map(renderLicense).mkString(", ")})"
    val versionControl0 = renderVersionControl(versionControl)
    val developers0 = s"Seq(${developers.map(renderDeveloper).mkString(", ")})"
    s"PomSettings($description0, $organization0, $url0, $licenses0, $versionControl0, $developers0)"
  }

  def renderLicense(value: License) = {
    import value.*
    val id0 = literalize(if (id == null) "" else id)
    val name0 = literalize(if (name == null) "" else name)
    val url0 = literalize(if (url == null) "" else url)
    val distribution0 = literalize(if (distribution == null) "" else distribution)
    s"License($id0, $name0, $url0, $isOsiApproved, $isFsfLibre, $distribution0)"
  }

  def renderVersionControl(value: VersionControl) = {
    import value.*
    val browsableRepository0 = browsableRepository.fold("None")(v => s"Some(${literalize(v)})")
    val connection0 = connection.fold("None")(v => s"Some(${literalize(v)})")
    val developerConnection0 = developerConnection.fold("None")(v => s"Some(${literalize(v)})")
    val tag0 = tag.fold("None")(v => s"Some(${literalize(v)})")
    s"VersionControl($browsableRepository0, $connection0, $developerConnection0, $tag0)"
  }

  def renderDeveloper(value: Developer) = {
    import value.*
    val id0 = literalize(if (id == null) "" else id)
    val name0 = literalize(if (name == null) "" else name)
    val url0 = literalize(if (url == null) "" else url)
    val organization0 = organization.fold("None")(v => s"Some(${literalize(v)})")
    val organizationUrl0 = organizationUrl.fold("None")(v => s"Some(${literalize(v)})")
    s"Developer($id0, $name0, $url0, $organization0, $organizationUrl0)"
  }

  def renderVersionScheme(value: String) = value match {
    case "early-semver" => "VersionScheme.EarlySemVer"
    case "pvp" => "VersionScheme.PVP"
    case "semver-spec" => "VersionScheme.SemVerSpec"
    case "strict" => "VersionScheme.Strict"
  }

  def renderBlocks(blocks: String*) = blocks.mkString(
    """
      |""".stripMargin,
    """
      |
      |""".stripMargin,
    """
      |""".stripMargin
  )

  def renderLines(lines: Iterable[String]) = lines.mkString(
    """
      |""".stripMargin
  )

  def renderMember[A](
      name: String,
      value: A,
      crossValues: Seq[(String, A)],
      renderValue: A => String
  ) = {
    val crossValues0 = crossValues.collect {
      case (k, v) if v != null => (k, v)
    }
    if (value == null && crossValues0.isEmpty) ""
    else {
      val value0 = if (value == null) s"super.$name" else renderValue(value)
      s"def $name = " + {
        if (crossValues0.isEmpty) value0
        else {
          val crossCases = crossValues0.groupMap(_._2)(_._1).toSeq.sortBy(_._2).map {
            (value, crosses) =>
              val matchValues = crosses.sorted.map(literalize(_)).mkString(" | ")
              s"case $matchValues => ${renderValue(value)}"
          }
          s"""crossValue match {
             |  ${renderLines(crossCases)}
             |  case _ => $value0
             |}""".stripMargin
        }
      }
    }
  }

  def renderTask[A](
      name: String,
      value: A,
      crossValues: Seq[(String, A)],
      renderValue: A => String
  ) = {
    val crossValues0 = crossValues.collect {
      case (k, v) if v != null => (k, v)
    }
    if (value == null && crossValues0.isEmpty) ""
    else {
      val value0 = if (value == null) s"super.$name()" else renderValue(value)
      s"def $name = " + {
        if (crossValues0.isEmpty) value0
        else {
          val crossCases = crossValues0.groupMap(_._2)(_._1).toSeq.sortBy(_._2).map {
            (value, crosses) =>
              val matchValues = crosses.sorted.map(literalize(_)).mkString(" | ")
              s"case $matchValues => ${renderValue(value)}"
          }
          s"""$renderCrossValueInTask match {
             |  ${renderLines(crossCases)}
             |  case _ => $value0
             |}""".stripMargin
        }
      }
    }
  }

  def renderMemberAsSeq[A](
      name: String,
      value: Seq[A],
      crossValues: Seq[(String, Seq[A])],
      renderValue: A => String
  ) = {
    val crossValues0 = crossValues.filter(_._2.nonEmpty)
    if (value.isEmpty && crossValues0.isEmpty) ""
    else {
      def renderSeq(value: Seq[A]) = value.map(renderValue).mkString("Seq(", ", ", ")")
      s"def $name = super.$name" + {
        if (value.isEmpty) "" else " ++ " + renderSeq(value)
      } + {
        if (crossValues0.isEmpty) ""
        else {
          val crossCases = crossValues0.groupMap(_._2)(_._1).toSeq.sortBy(_._2).map {
            (value, crosses) =>
              val matchValues = crosses.sorted.map(literalize(_)).mkString(" | ")
              s"case $matchValues => ${renderSeq(value)}"
          }
          s""" ++ (crossValue match {
             |  ${renderLines(crossCases)}
             |  case _ => Nil
             |})""".stripMargin
        }
      }
    }
  }

  def renderTaskAsMap[K, V](
      name: String,
      value: Map[K, V],
      crossValues: Seq[(String, Map[K, V])],
      renderKey: K => String,
      renderValue: V => String
  ) = {
    val crossValues0 = crossValues.filter(_._2.nonEmpty)
    if (value.isEmpty && crossValues0.isEmpty) ""
    else {
      def renderMap(value: Map[K, V]) = value
        .map((k, v) => s"(${renderKey(k)}, ${renderValue(v)})")
        .mkString("Map(", ", ", ")")
      s"def $name = super.$name()" + {
        if (value.isEmpty) "" else " ++ " + renderMap(value)
      } + {
        if (crossValues0.isEmpty) ""
        else {
          val crossCases = crossValues0.groupMap(_._2)(_._1).toSeq.sortBy(_._2).map {
            (value, crosses) =>
              val matchValues = crosses.sorted.map(literalize(_)).mkString(" | ")
              s"case $matchValues => ${renderMap(value)}"
          }
          s""" ++ ($renderCrossValueInTask match {
             |  ${renderLines(crossCases)}
             |  case _ => Map()
             |})""".stripMargin
        }
      }
    }
  }

  def renderTaskAsSeq[A](
      name: String,
      value: Seq[A],
      crossValues: Seq[(String, Seq[A])],
      renderValue: A => String
  ) = {
    val crossValues0 = crossValues.filter(_._2.nonEmpty)
    if (value.isEmpty && crossValues0.isEmpty) ""
    else {
      def renderSeq(value: Seq[A]) = value.map(renderValue).mkString("Seq(", ", ", ")")
      s"def $name = super.$name()" + {
        if (value.isEmpty) "" else " ++ " + renderSeq(value)
      } + {
        if (crossValues0.isEmpty) ""
        else {
          val crossCases = crossValues0.groupMap(_._2)(_._1).toSeq.sortBy(_._2).map {
            (value, crosses) =>
              val matchValues = crosses.sorted.map(literalize(_)).mkString(" | ")
              s"case $matchValues => ${renderSeq(value)}"
          }
          s""" ++ ($renderCrossValueInTask match {
             |  ${renderLines(crossCases)}
             |  case _ => Nil
             |})""".stripMargin
        }
      }
    }
  }
}
