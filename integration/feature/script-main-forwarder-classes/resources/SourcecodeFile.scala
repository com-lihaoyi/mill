@main
def main1(): Unit =
  val path = os.Path(sourcecode.File())
  assert(os.isFile(path), path.toString)
  assert(!path.toString.contains("allSourceFiles.dest"), path.toString)
  println(path.last)
