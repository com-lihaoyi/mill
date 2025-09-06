package mill.kotlinlib.worker.api

sealed trait KotlinWorkerTarget

object KotlinWorkerTarget {
  case object Jvm extends KotlinWorkerTarget
  case object Js extends KotlinWorkerTarget
}
