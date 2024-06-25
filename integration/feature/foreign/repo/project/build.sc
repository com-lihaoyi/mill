import $file.other

import mill._

def assertPathsEqual(p1: os.Path, p2: os.Path): Unit = if (p1 != p2) throw new Exception(
  s"Paths were not equal : \n- $p1 \n- $p2"
)
def assertPathsNotEqual(p1: os.Path, p2: os.Path): Unit = if (p1 == p2) throw new Exception(
  s"Paths were equal : \n- $p1 \n- $p2"
)

object sub extends PathAware with DestAware {

  object sub extends PathAware with DestAware

  object sub2 extends millbuild.outer.PathAware with millbuild.outer.DestAware

}

def checkProjectPaths = T {
  val thisPath: os.Path = millSourcePath
  assert(thisPath.last == "project")
  assertPathsEqual(sub.selfPath(), thisPath / "sub")
  assertPathsEqual(sub.sub.selfPath(), thisPath / "sub" / "sub")
  assertPathsEqual(sub.sub2.selfPath(), thisPath / "sub" / "sub2")
}

def checkInnerPaths = T {
  val thisPath: os.Path = millSourcePath
  assertPathsEqual(millbuild.inner.millSourcePath, thisPath / "inner")
  assertPathsEqual(millbuild.inner.sub.selfPath(), thisPath / "inner" / "sub")
  assertPathsEqual(millbuild.inner.sub.sub.selfPath(), thisPath / "inner" / "sub" / "sub")
}

def checkOuterPaths = T {
  val thisPath: os.Path = millSourcePath
  assertPathsEqual(millbuild.outer.millSourcePath, thisPath / os.up / "outer")
  assertPathsEqual(millbuild.outer.sub.selfPath(), thisPath / os.up / "outer" / "sub")
  assertPathsEqual(millbuild.outer.sub.sub.selfPath(), thisPath / os.up / "outer" / "sub" / "sub")

  // covers the case where millSourcePath is modified in a submodule
  assertPathsNotEqual(
    millbuild.outer.sourcepathmod.jvm.selfDest(),
    millbuild.outer.sourcepathmod.js.selfDest()
  )
}

def checkOuterInnerPaths = T {
  val thisPath: os.Path = millSourcePath
  assertPathsEqual(millbuild.outer.inner.millSourcePath, thisPath / os.up / "outer" / "inner")
  assertPathsEqual(millbuild.outer.inner.sub.selfPath(), thisPath / os.up / "outer" / "inner" / "sub")
  assertPathsEqual(
    millbuild.outer.inner.sub.sub.selfPath(),
    thisPath / os.up / "outer" / "inner" / "sub" / "sub"
  )
}

def checkOtherPaths = T {
  val thisPath: os.Path = millSourcePath
  assertPathsEqual(millbuild.other.millSourcePath, thisPath)
  assertPathsEqual(millbuild.other.sub.selfPath(), thisPath / "sub")
  assertPathsEqual(millbuild.other.sub.sub.selfPath(), thisPath / "sub" / "sub")
}

def checkProjectDests = T {
  val outPath: os.Path = millSourcePath / "out"
  assertPathsEqual(sub.selfDest(), outPath / "sub")
  assertPathsEqual(sub.sub.selfDest(), outPath / "sub" / "sub")
  assertPathsEqual(sub.sub2.selfDest(), outPath / "sub" / "sub2")
}

def checkInnerDests = T {
  val foreignOut: os.Path = millSourcePath / "out" / "foreign-modules"
  assertPathsEqual(millbuild.inner.sub.selfDest(), foreignOut / "inner" / "build" / "sub")
  assertPathsEqual(millbuild.inner.sub.sub.selfDest(), foreignOut / "inner" / "build" / "sub" / "sub")
}

def checkOuterDests = T {
  val foreignOut: os.Path = millSourcePath / "out" / "foreign-modules"
  assertPathsEqual(millbuild.outer.sub.selfDest(), foreignOut / "up-1" / "outer" / "build" / "sub")
  assertPathsEqual(
    millbuild.outer.sub.sub.selfDest(),
    foreignOut / "up-1" / "outer" / "build" / "sub" / "sub"
  )
}

def checkOuterInnerDests = T {
  val foreignOut: os.Path = millSourcePath / "out" / "foreign-modules"
  assertPathsEqual(
    millbuild.outer.inner.sub.selfDest(),
    foreignOut / "up-1" / "outer" / "inner" / "build" / "sub"
  )
  assertPathsEqual(
    millbuild.outer.inner.sub.sub.selfDest(),
    foreignOut / "up-1" / "outer" / "inner" / "build" / "sub" / "sub"
  )
}

def checkOtherDests = T {
  val foreignOut: os.Path = millSourcePath / "out" / "foreign-modules"
  assertPathsEqual(millbuild.other.sub.selfDest(), foreignOut / "other" / "sub")
  assertPathsEqual(millbuild.other.sub.sub.selfDest(), foreignOut / "other" / "sub" / "sub")
}

trait PathAware extends mill.Module {

  def selfPath = T { millSourcePath }
}

trait DestAware extends mill.Module {
  def selfDest = T { T.dest / os.up }
}
