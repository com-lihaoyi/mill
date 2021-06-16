package mill.main
import ammonite.runtime.ImportHook.BaseIvy
import ammonite.runtime.ImportHook
import java.io.File

/**
 * Overrides the ivy hook to interpret $MILL_VERSION as the version of mill
 * the user runs.
 *
 * Can be used to ensure loaded contrib modules keep up to date.
 */
object MillIvyHook extends BaseIvy(plugin = false) {
  override def resolve(
      interp: ImportHook.InterpreterInterface,
      signatures: Seq[String]
  ): Either[String, (Seq[coursierapi.Dependency], Seq[File])] =
    super.resolve(interp, signatures.map(_.replace("$MILL_VERSION", mill.BuildInfo.millVersion)))
}
