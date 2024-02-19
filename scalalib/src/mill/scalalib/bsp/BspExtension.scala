package mill.scalalib.bsp

import mill.Agg
import mill.scalalib.Dep

/**
 * A BSP Extension to be loaded by the Mill BSP server.
 * @param className The extension class to be loaded.
 * @param ivyDeps Additional dependencies to be loaded into the BSP server classpath.
 */
case class BspExtension(
    className: String,
    ivyDeps: Agg[Dep]
)
