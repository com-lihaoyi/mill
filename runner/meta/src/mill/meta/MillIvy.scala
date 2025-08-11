package mill.meta

object MillIvy {
  def processMillMvnDepsignature(signatures: Seq[String]): Seq[String] = {
    val millSigs: Seq[String] =
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
    millSigs.map(substituteMillVersion)
  }

  def substituteMillVersion(str: String) = str
    .replace("$MILL_VERSION", mill.util.BuildInfo.millVersion)
    .replace("${MILL_VERSION}", mill.util.BuildInfo.millVersion)
    .replace("$MILL_BIN_PLATFORM", mill.util.BuildInfo.millBinPlatform)
    .replace("${MILL_BIN_PLATFORM}", mill.util.BuildInfo.millBinPlatform)

}
