package mill.init.migrate
package sbt

import mill.constants.CodeGenConstants.rootModuleAlias
import mill.internal.Util.backtickWrap
import pprint.Util.literalize

import java.io.PrintStream
import scala.collection.immutable.SortedSet

class SbtPackageWriter(
    platformCrossTypeBySegments: Map[Seq[String], String],
    crossScalaVersionsBySegments: Map[Seq[String], Seq[String]],
    depsObjectName: String,
    namesByDep: Map[String, String]
) extends PackageWriter {

  override def writePackage(ps: PrintStream, pkg: PackageRepr): Unit = {
    super.writePackage(ps, pkg)
  }

  def computeImports(pkg: PackageRepr) = {
    val b = SortedSet.newBuilder[String]
    b += "mill.scalalib._"
    for supertype <- pkg.modules.iterator.flatMap(_.main.supertypes) do
      supertype match
        case "ScalaJSModule" => b += "mill.scalajslib._"
        case "ScalaNativeModule" => b += "mill.scalanativelib._"
        case "PublishModule" => b += "mill.javalib.publish._"
        case "CrossSbtModule" | "CrossSbtPlatformModule" | "CrossScalaVersionRanges" | "Module" =>
          b += "mill.api._"
        case _ =>
    if (pkg.segments.nonEmpty) b += s"$rootModuleAlias._"
    b.result()
  }

  override def writeModule(
      ps: PrintStream,
      segments: Seq[String],
      module: ModuleRepr,
      embed: () => Unit
  ): Unit = {
    crossScalaVersionsBySegments.get(segments) match
      case Some(versions) =>
        import module.*
        val crossTraitName = segments.lastOption.getOrElse(os.pwd.last).split("\\W")
          .iterator.map(_.capitalize).mkString("", "", "Module")
        import main.*
        ps.print("object ")
        ps.print(backtickWrap(name))
        ps.print(" extends Cross[")
        ps.print(crossTraitName)
        ps.print("](")
        ps.print(literalize(versions.head))
        for version <- versions.tail do
          ps.print(", ")
          ps.print(literalize(version))
        ps.println(')')
        ps.print("trait ")
        ps.print(crossTraitName)
        writeExtendsClause(ps, supertypes)
        ps.println(" {")
        writeModuleConfigs(ps, configs)
        writePlatformSharedSourceRoots(ps, segments)
        for (testModule <- test) do writeBaseModule(ps, testModule)
        embed()
        if (segments.isEmpty) writeBuildTypes(ps)
        ps.println('}')
      case None =>
        super.writeModule(
          ps,
          segments,
          module,
          () =>
            writePlatformSharedSourceRoots(ps, segments)
            embed()
            if (segments.isEmpty) writeBuildTypes(ps)
        )
  }

  def writeBuildTypes(ps: PrintStream) = {
    writeNamedDepsObject(ps)
    writeCustomTraits(ps)
  }

  def writeNamedDepsObject(ps: PrintStream) = {
    ps.println()
    ps.print("object ")
    ps.print(depsObjectName)
    ps.println(" {")
    for (dep, name) <- namesByDep.toSeq.sortBy(_._2) do
      ps.println()
      ps.print("val ")
      ps.print(name)
      ps.print(" = ")
      super.writeDep(ps, dep)
    ps.println()
    ps.println('}')
  }

  override def writeDep(ps: PrintStream, dep: String) = {
    ps.print(depsObjectName)
    ps.print('.')
    ps.print(namesByDep(dep))
  }

  def writePlatformSharedSourceRoots(ps: PrintStream, segments: Seq[String]) = {
    for (crossType <- platformCrossTypeBySegments.get(segments))
      ps.println()
      ps.print("def platformCrossType = ")
      ps.print(literalize(crossType))
  }

  def writeCustomTraits(ps: PrintStream) = {
    if (platformCrossTypeBySegments.nonEmpty)
      ps.println()
      ps.println(renderSbtPlatformModule)
      if (crossScalaVersionsBySegments.nonEmpty)
        ps.println()
        ps.println(renderCrossSbtPlatformModule)
  }

  def renderSbtPlatformModule =
    s"""trait SbtPlatformModule extends SbtModule { outer =>
       |
       |  def platformCrossType = ""
       |
       |  def platformSharedSourceRoots = {
       |    def partials(platforms: String*) = platforms.diff(moduleDir.last).iterator
       |      .map(dir => os.up / Seq(dir, moduleDir.last).sorted.mkString("-")).toSeq
       |    platformCrossType match {
       |      case "Full" => os.up / "shared" +: partials("js", "jvm", "native")
       |      case "Pure" => os.up +: partials(".js", ".jvm", ".native")
       |      case "Dummy" => partials("js", "jvm", "native")
       |      case _ => Nil
       |    }
       |  }
       |
       |  override def sources = Task {
       |    BuildCtx.withFilesystemCheckerDisabled {
       |      super.sources() ++ platformSharedSourceRoots.flatMap(rel =>
       |        Seq(
       |          PathRef(moduleDir / rel / "src/main/scala"),
       |          PathRef(moduleDir / rel / "src/main/java")
       |        )
       |      )
       |    }
       |  }
       |
       |  trait SbtPlatformTests extends SbtTests {
       |
       |    override def sources = Task {
       |      BuildCtx.withFilesystemCheckerDisabled {
       |        super.sources() ++ outer.platformSharedSourceRoots.flatMap(rel =>
       |          Seq(
       |            PathRef(moduleDir / rel / "src/test/scala"),
       |            PathRef(moduleDir / rel / "src/test/java")
       |          )
       |        )
       |      }
       |    }
       |  }
       |}""".stripMargin

  def renderCrossSbtPlatformModule =
    s"""trait CrossSbtPlatformModule extends CrossSbtModule with SbtPlatformModule { outer =>
       |
       |  override def sources = Task {
       |    BuildCtx.withFilesystemCheckerDisabled {
       |      super.sources() ++ scalaVersionDirectoryNames.flatMap(s =>
       |        platformSharedSourceRoots.map(rel =>
       |          PathRef(moduleDir / rel / "src/main" / s"scala-$$s")
       |        )
       |      )
       |    }
       |  }
       |
       |  trait CrossSbtPlatformTests extends CrossSbtTests with SbtPlatformTests  {
       |
       |    override def sources = Task {
       |      BuildCtx.withFilesystemCheckerDisabled {
       |        super.sources() ++ scalaVersionDirectoryNames.flatMap(s =>
       |          outer.platformSharedSourceRoots.map(rel =>
       |            PathRef(moduleDir / rel / "src/test" / s"scala-$$s")
       |          )
       |        )
       |      }
       |    }
       |  }
       |}""".stripMargin
}
