package bar

object Bar {
  def value: String = java.util.Optional.of("bar").get
}
