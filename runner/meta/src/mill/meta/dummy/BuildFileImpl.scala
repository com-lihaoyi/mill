package mill.meta.dummy

import mill.api.internal.BuildFileCls

/**
 * Pre-compiled BuildFileImpl for the dummy build.
 * Provides access to the package_ root module.
 */
object BuildFileImpl extends BuildFileCls(package_)
