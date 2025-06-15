package mill.api
import mill.constants.EnvVars
import scala.util.DynamicVariable
import java.nio.file.Path
private[mill] object WorkspaceRoot {
  val workspaceRoot0: scala.util.DynamicVariable[Path] =
    DynamicVariable(sys.env.get(EnvVars.MILL_WORKSPACE_ROOT).fold(Path.of("."))(Path.of(_)))
}
