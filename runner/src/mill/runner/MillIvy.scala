package mill.runner


object MillIvy {
  def processMillIvyDepSignature(signatures: Set[String]): Set[String] = {
    // replace platform notation and empty version
    val millSigs: Set[String] = for (signature <- signatures) yield {

      if (signature.endsWith(":") && signature.count(_ == ":") == 4) signature + "$MILL_VERSION"
      //      else
      signature.split("[:]") match {
        case Array(org, "", pname, "", version)
          if org.length > 0 && pname.length > 0 && version.length > 0 =>
          s"${org}::${pname}_mill$$MILL_BIN_PLATFORM:${version}"
        case Array(org, "", "", pname, "", version)
          if org.length > 0 && pname.length > 0 && version.length > 0 =>
          s"${org}:::${pname}_mill$$MILL_BIN_PLATFORM:${version}"
        case Array(org, "", name) if org.length > 0 && name.length > 0 && signature.endsWith(":") =>
          s"${org}::${name}:$$MILL_VERSION"
        case _ => signature
      }
    }

    val replaced = millSigs.map(_
      .replace("$MILL_VERSION", mill.BuildInfo.millVersion)
      .replace("${MILL_VERSION}", mill.BuildInfo.millVersion)
      .replace("$MILL_BIN_PLATFORM", mill.BuildInfo.millBinPlatform)
      .replace("${MILL_BIN_PLATFORM}", mill.BuildInfo.millBinPlatform))

    replaced
  }
}
