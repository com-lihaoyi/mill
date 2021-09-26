package mill.main
import ammonite.runtime.ImportHook.BaseIvy
import ammonite.runtime.ImportHook
import java.io.File

import mill.BuildInfo

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
 */
object MillIvyHook extends BaseIvy(plugin = false) {
  override def resolve(
      interp: ImportHook.InterpreterInterface,
      signatures: Seq[String]
  ): Either[String, (Seq[coursierapi.Dependency], Seq[File])] = {

    // replace platform notation
    val millSigs: Seq[String] = for (signature <- signatures) yield {
      signature.split("[:]") match {
        case Array(org, "", pname, "", version)
            if org.length > 0 && pname.length > 0 && version.length > 0 =>
          s"${org}::${pname}_mill$$MILL_BIN_PLATFORM:${version}"
        case Array(org, "", "", pname, "", version)
            if org.length > 0 && pname.length > 0 && version.length > 0 =>
          s"${org}:::${pname}_mill$$MILL_BIN_PLATFORM:${version}"
        case _ => signature
      }
    }
    // replace variables
    val replaced = millSigs.map(_
      .replace("$MILL_VERSION", mill.BuildInfo.millVersion)
      .replace("${MILL_VERSION}", mill.BuildInfo.millVersion)
      .replace("$MILL_BIN_PLATFORM", mill.BuildInfo.millBinPlatform)
      .replace("${MILL_BIN_PLATFORM}", mill.BuildInfo.millBinPlatform))

    super.resolve(interp, replaced)
  }
}
