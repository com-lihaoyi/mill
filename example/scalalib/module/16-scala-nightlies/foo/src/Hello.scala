@main
def main(me: String*) = {
  println(
    s"Hello ${me.headOption.getOrElse("world")}, this is Scala '${scala.util.Properties.versionNumberString}'"
  )
}
