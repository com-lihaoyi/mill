// Issue https://github.com/com-lihaoyi/mill/issues/1901
import mill._
import mill.scalalib._

object app extends SbtModule {

  def scalaVersion = "2.13.10"

  def scalacOptions = Seq("-Vclasspath")

  def ivyDeps = Agg(
    ivy"io.getquill::quill-sql:3.18.0"
  )
}
