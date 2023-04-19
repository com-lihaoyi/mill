// == Unmanaged Jars

import mill._, scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.13.8"
  def unmanagedClasspath = T {
    if (!os.exists(millSourcePath / "lib")) Agg()
    else Agg.from(os.list(millSourcePath / "lib").map(PathRef(_)))
  }
}

// You can override `unmanagedClasspath` to point it at any jars you place on the
// filesystem, e.g. in the above snippet any jars that happen to live in the
// `foo/lib/` folder.

/* Example Usage

> ./mill run '{"name":"John","age":30}'
Key: name, Value: John
Key: age, Value: 30

*/