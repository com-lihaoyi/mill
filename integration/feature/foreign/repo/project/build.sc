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

  object sub2 extends build.outer.PathAware with build.outer.DestAware

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
  assertPathsEqual(inner.millSourcePath, thisPath / "inner")
  assertPathsEqual(inner.sub.selfPath(), thisPath / "inner" / "sub")
  assertPathsEqual(inner.sub.sub.selfPath(), thisPath / "inner" / "sub" / "sub")
}

def checkOuterPaths = T {
  val thisPath: os.Path = millSourcePath
  assertPathsEqual(outer.millSourcePath, thisPath / "outer")
  assertPathsEqual(outer.sub.selfPath(), thisPath / "outer" / "sub")
  assertPathsEqual(outer.sub.sub.selfPath(), thisPath / "outer" / "sub" / "sub")

  // covers the case where millSourcePath is modified in a submodule
  assertPathsNotEqual(
    outer.sourcepathmod.jvm.selfDest(),
    outer.sourcepathmod.js.selfDest()
  )
}

def checkOuterInnerPaths = T {
  val thisPath: os.Path = millSourcePath
  assertPathsEqual(outer.inner.millSourcePath, thisPath / "outer" / "inner")
  assertPathsEqual(outer.inner.sub.selfPath(), thisPath / "outer" / "inner" / "sub")
  assertPathsEqual(
    outer.inner.sub.sub.selfPath(),
    thisPath / "outer" / "inner" / "sub" / "sub"
  )
}

def checkOtherPaths = T {
  val thisPath: os.Path = millSourcePath
  assertPathsEqual(other.millSourcePath, thisPath)
  assertPathsEqual(other.sub.selfPath(), thisPath / "sub")
  assertPathsEqual(other.sub.sub.selfPath(), thisPath / "sub" / "sub")
}

def checkProjectDests = T {
  val outPath = millSourcePath / "out"
  assertPathsEqual(sub.selfDest(), outPath / "sub")
  assertPathsEqual(sub.sub.selfDest(), outPath / "sub" / "sub")
  assertPathsEqual(sub.sub2.selfDest(), outPath / "sub" / "sub2")
}

def checkInnerDests = T {
  val outPath = millSourcePath / "out"
  assertPathsEqual(inner.sub.selfDest(), outPath / "inner" / "sub")
  assertPathsEqual(inner.sub.sub.selfDest(), outPath / "inner" / "sub" / "sub")
}

def checkOuterDests = T {
  val outPath = millSourcePath / "out"
  assertPathsEqual(outer.sub.selfDest(), outPath / "outer" / "sub")
  assertPathsEqual(
    outer.sub.sub.selfDest(),
    outPath / "outer" / "sub" / "sub"
  )
}

def checkOuterInnerDests = T {
  val outPath = millSourcePath / "out"
  assertPathsEqual(
    outer.inner.sub.selfDest(),
    outPath / "outer" / "inner" / "sub"
  )
  assertPathsEqual(
    outer.inner.sub.sub.selfDest(),
    outPath / "outer" / "inner" / "sub" / "sub"
  )
}

def checkOtherDests = T {
  val outPath = millSourcePath / "out"
  assertPathsEqual(other.sub.selfDest(), outPath / "other" / "sub")
  assertPathsEqual(other.sub.sub.selfDest(), outPath / "other" / "sub" / "sub")
}

trait PathAware extends mill.Module {

  def selfPath = T { millSourcePath }
}

trait DestAware extends mill.Module {
  def selfDest = T { T.dest / os.up }
}
