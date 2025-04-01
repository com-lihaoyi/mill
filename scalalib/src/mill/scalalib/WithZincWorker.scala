package mill.scalalib

import mill.define.{Module, ModuleRef}

import scala.annotation.nowarn

/**
 * Common trait for modules that use either a custom or a globally shared [[ZincWorkerModule]].
 *
 * Deprecation info: This will be renamed to WithJvmWorker in Mill 0.13
 */
@deprecated("This will be renamed to WithJvmWorker in Mill 0.13", "Mill 0.12.11")
trait WithZincWorker extends Module {
  @deprecated("Use jvmWorker for read access. This def will be renamed to `jvmWorker` in Mill 0.13", "Mill 0.12.11")
  def zincWorker: ModuleRef[ZincWorkerModule] = ModuleRef(ZincWorkerModule)
  @nowarn("cat=deprecation")
  final def jvmWorker: ModuleRef[JvmWorkerModule] = zincWorker
}
