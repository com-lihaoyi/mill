package mill.main
import ammonite.runtime.ImportHook.BaseIvy
import ammonite.runtime.ImportHook

import java.io.File

/**
 * Overrides the ivy hook to customize the `$ivy`-import with mill specifics:
 *
 * - interpret `$MILL_VERSION` as the mill version
 *
 * - interpret `$MILL_BIN_PLATFORM` as the mill binary platform version
 *
 * - supports the format `org::name::version` for mill plugins;
 *   which is equivalent to `org::name_mill$MILL_BIN_PLATFORM:version`
 *
 * - supports the format `org:::name::version` for mill plugins;
 *   which is equivalent to `org:::name_mill$MILL_BIN_PLATFORM:version`
 *
 * - replaces the empty version for scala dependencies as $MILL_VERSION
 */
object MillIvyHook extends BaseIvy(plugin = false) {
  override def resolve(
      interp: ImportHook.InterpreterInterface,
      signatures: Seq[String]
  ): Either[String, (Seq[coursierapi.Dependency], Seq[File])] = {
    val replaced = MillIvy.processMillIvyDepSignature(signatures)
    super.resolve(interp, replaced)
  }
}

object MillIvy {
  def processMillIvyDepSignature(signatures: Seq[String]): Seq[String] = {
    // replace platform notation and empty version
    val millSigs: Seq[String] = for (signature <- signatures) yield {

      if (signature.endsWith(":") && signature.count(_ == ":") == 4) signature + "$MILL_VERSION"
      //      else
      signature.split("[:]") match {
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
    }

    val replaced = millSigs.map(_
      .replace("$MILL_VERSION", mill.BuildInfo.millVersion)
      .replace("${MILL_VERSION}", mill.BuildInfo.millVersion)
      .replace("$MILL_BIN_PLATFORM", mill.BuildInfo.millBinPlatform)
      .replace("${MILL_BIN_PLATFORM}", mill.BuildInfo.millBinPlatform))

    replaced
  }
}
