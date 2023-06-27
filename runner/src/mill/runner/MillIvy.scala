package mill.runner

object MillIvy {
  def processMillIvyDepSignature(signatures: Set[String]): Set[String] = {
    val millSigs: Set[String] =
      for (signature <- signatures) yield {
        signature.split("[:]") match {
          case Array(org, "", name)
              if org.length > 0 && name.length > 0 && signature.endsWith(":") =>
            // replace empty version with Mill Version Placeholder
            signature + "$MILL_VERSION"
          case _ => signature
        }
      }

    // replace special MILL_ placeholders
    val replaced = millSigs.map(_
      .replace("$MILL_VERSION", mill.main.BuildInfo.millVersion)
      .replace("${MILL_VERSION}", mill.main.BuildInfo.millVersion)
      .replace("$MILL_BIN_PLATFORM", mill.main.BuildInfo.millBinPlatform)
      .replace("${MILL_BIN_PLATFORM}", mill.main.BuildInfo.millBinPlatform))

    replaced
  }
}
