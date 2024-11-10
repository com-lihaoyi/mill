/*
 * Original code copied from https://github.com/lefou/mill-kotlin
 * Original code published under the Apache License Version 2
 * Original Copyright 2020-2024 Tobias Roeser
 */
package mill.kotlinlib

import mill.api.{Ctx, PathRef}
import mill.kotlinlib.worker.api.KotlinWorker

trait KotlinWorkerManager {
  def get(toolsClasspath: Seq[PathRef])(implicit ctx: Ctx): KotlinWorker
}
