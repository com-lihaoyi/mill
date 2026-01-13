package mill.meta.dummy

import mill.api.internal.RootModule

/**
 * Pre-compiled MillMiscInfo that reads paths from environment variables at runtime.
 * This allows the dummy build to work with any project directory without needing
 * to be recompiled for each project.
 */
object MillMiscInfo extends RootModule.Info(
  RootModule.Info.fromEnv().projectRoot,
  RootModule.Info.fromEnv().output,
  RootModule.Info.fromEnv().topLevelProjectRoot
)
