package mill.util.internal

import mill.api.internal.BuildFileCls

/**
 * Pre-compiled BuildFileImpl for the dummy build.
 * Provides access to the DummyModule root module.
 */
private[mill] object DummyBuildFile extends BuildFileCls(DummyModule)
