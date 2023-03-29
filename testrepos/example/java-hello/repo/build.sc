import mill._, scalalib._

object foo extends JavaModule{
  def moduleDeps = Seq(bar)
}

object bar extends JavaModule