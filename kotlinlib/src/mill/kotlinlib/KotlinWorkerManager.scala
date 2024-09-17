/*
 * Copyright 2020-Present Original lefou/mill-kotlin repository contributors.
 */

package mill.kotlinlib

import mill.api.{Ctx, PathRef}
import mill.kotlinlib.worker.api.KotlinWorker

trait KotlinWorkerManager {
  def get(toolsClasspath: Seq[PathRef])(implicit ctx: Ctx): KotlinWorker
}
