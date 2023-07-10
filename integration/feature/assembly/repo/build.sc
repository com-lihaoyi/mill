import mill._, scalalib._

trait Setup extends ScalaModule {
  def scalaVersion = "2.13.11"
  def sources = T.sources(T.workspace / "src")
  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"com.lihaoyi::scalatags:0.8.2",
    ivy"com.lihaoyi::mainargs:0.4.0",
    ivy"org.apache.avro:avro:1.11.1"
  )
}
trait ExtraDeps extends ScalaModule {
  def ivyDeps = super.ivyDeps() ++ Agg(
    ivy"dev.zio::zio:2.0.15",
    ivy"org.typelevel::cats-core:2.9.0",
    ivy"org.apache.spark::spark-core:3.4.0",
    ivy"dev.zio::zio-metrics-connectors:2.0.8",
    ivy"dev.zio::zio-http:3.0.0-RC2"
  )
}

object noExe extends Module {
  object small extends Setup {
    override def prependShellScript: T[String] = ""
  }
  object large extends Setup with ExtraDeps {
    override def prependShellScript: T[String] = ""
  }
}

object exe extends Module {
  object small extends Setup
  object large extends Setup with ExtraDeps
}
