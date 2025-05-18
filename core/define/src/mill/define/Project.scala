package mill.define

import mill.constants.EnvVars
object Project {
  val workspaceRoot: os.Path =
    sys.env.get(EnvVars.MILL_WORKSPACE_ROOT).fold(os.pwd)(os.Path(_, os.pwd))

  def withFilesystemCheckerDisabled[T](block: => T): T = 
    os.checker.withValue(os.Checker.Nop) { block }
}
