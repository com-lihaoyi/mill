package mill.init.migrate

object BuildDefaults:

  def moduleSupertype0 = "mill.api.Module"

  def moduleSupertypeCross = "mill.api.Cross"

  def moduleSupertypes(data: ModuleData) =
    val b = Seq.newBuilder[String]
    import data._
    // TODO minimize
    if (coursierModule.nonEmpty) b.+=("mill.javalib.CoursierModule")
    if (javaHomeModule.nonEmpty) b.+=("mill.javalib.JavaHomeModule")
    if (runModule.nonEmpty) b.+=("mill.javalib.RunModule")
    if (javaModule.nonEmpty) b += "mill.javalib.JavaModule"
    if (publishModule.nonEmpty) b += "mill.javalib.PublishModule"
    if (scalaModule.nonEmpty) b += "mill.scalalib.ScalaModule"
    if (scalaJSModule.nonEmpty) b += "mill.scalajslib.ScalaJSModule"
    if (scalaNativeModule.nonEmpty) b += "mill.scalanativelib.ScalaNativeModule"
    b.result()

  def typeName(name: String, suffix: String) =
    name.split("\\W").iterator.map(_.capitalize).mkString("", "", suffix)
