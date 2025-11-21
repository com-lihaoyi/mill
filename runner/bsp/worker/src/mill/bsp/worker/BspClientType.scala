package mill.bsp.worker

/** Used to handle edge cases for specific BSP clients. */
enum BspClientType {

  /** Intellij IDEA */
  case IntellijBSP

  /** Any other BSP client */
  case Other(displayName: String)

  def mergeResourcesIntoClasses: Boolean =
    this match {
      case IntellijBSP => true
      case Other(_) => false
    }
}
