package mill.api

object MillProperties{
  def millProperty(key: String): Option[String] =
    Option(sys.props(key)) // System property has priority
      .orElse(Option(LongMillProps.getProperty(key)))

  private val LongMillProps = {
    val props = new java.util.Properties()
    val millOptionsPath = sys.props("MILL_OPTIONS_PATH")
    if (millOptionsPath != null) props.load(new java.io.FileInputStream(millOptionsPath))
    props
  }
}
