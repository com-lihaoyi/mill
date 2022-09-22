package mill.scalalib.buildfile

import mill.api.JsonFormatters._
import mill.api.{PathRef, experimental}
import mill.buildfile.{AmmoniteParser, ParsedMillSetup, ReadDirectives}
import mill.define.{Module, Source, Task}
import mill.scalalib.{Dep, DepSyntax}
import mill.{BuildInfo, T}

/** A module that scans a mill project by inspecting the `build.sc`. */
@experimental
trait MillSetupScannerModule extends Module {

  /** The root path of the Mill project to scan. */
  def millProjectPath: T[os.Path] = T { millSourcePath }

  /** The Mill buildfile. */
  def buildScFile: Source = T.source(millProjectPath() / "build.sc")

  def supportUsingDirectives: T[Boolean] = false

  /** The Mill project setup. */
  def parsedMillSetup: T[ParsedMillSetup] = T {
    val buildSc = buildScFile().path

    val pass1 =
      if (supportUsingDirectives()) {
        ReadDirectives.readUsingDirectives(buildSc, parseVersionFiles = true)
      } else {
        ParsedMillSetup(
          projectDir = millProjectPath(),
          directives = ReadDirectives.readVersionFiles(buildSc / os.up),
          buildScript = Option(buildScFile().path).filter(os.exists)
        )
      }
    val pass2 = AmmoniteParser.parseAmmoniteImports(pass1)
    pass2
  }

  def millVersion: T[String] = T {
    parsedMillSetup().millVersion.getOrElse(BuildInfo.millVersion)
  }

  def millBinPlatform: T[String] = T {
    millVersion().split("[.]") match {
      case Array("0", "0" | "1" | "2" | "3" | "4" | "5", _*) => ""
      case Array("0", a, _*) => s"0.${a}"
      // TODO: support Milestone releases
    }
  }

  def includedSourceFiles: T[Seq[PathRef]] = T {
    parsedMillSetup().includedSourceFiles.map(PathRef(_))
  }

  protected def parseDeps: Task[String => Dep] = T.task { signature: String =>
    val mv = millVersion()
    val mbp = millBinPlatform()

    // a missing version means, we want the Mill version
    val millSig = signature.split("[:]") match {
      case Array(org, "", pname, "", version)
          if org.length > 0 && pname.length > 0 && version.length > 0 =>
        s"${org}::${pname}_mill$$MILL_BIN_PLATFORM:${version}"
      case Array(org, "", "", pname, "", version)
          if org.length > 0 && pname.length > 0 && version.length > 0 =>
        s"${org}:::${pname}_mill$$MILL_BIN_PLATFORM:${version}"
      case Array(org, "", name) if org.length > 0 && name.length > 0 && signature.endsWith(":") =>
        s"${org}::${name}:$$MILL_VERSION"
      case _ => signature
    }

    // replace variables
    val replaced = millSig
      .replace("$MILL_VERSION", mv)
      .replace("${MILL_VERSION}", mv)
      .replace("$MILL_BIN_PLATFORM", mbp)
      .replace("${MILL_BIN_PLATFORM}", mbp)

    ivy"${replaced}"
  }

}
