package millbuild

trait MillJava11ScalaModule extends MillPublishScalaModule {
  def scalaVersion = Deps.scalaVersionJava11

  def jvmId = "11"
}
