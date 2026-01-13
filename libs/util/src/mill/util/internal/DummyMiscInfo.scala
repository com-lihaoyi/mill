package mill.util.internal

import mill.api.internal.RootModule

/**
 * Pre-compiled MillMiscInfo that reads paths from environment variables at runtime.
 * This allows the dummy build to work with any project directory without needing
 * to be recompiled for each project.
 */
object DummyMiscInfo extends RootModule.Info.FromEnv
