package mill.scalalib

import scala.annotation.nowarn

package object api {
  @nowarn("cat=deprecation")
  type JvmWorkerApi = ZincWorkerApi
  @nowarn("cat=deprecation")
  val JvmWorkerApi = ZincWorkerApi

  @nowarn("cat=deprecation")
  type JvmWorkerUtil = ZincWorkerUtil
  @nowarn("cat=deprecation")
  val JvmWorkerUtil = ZincWorkerUtil

}
