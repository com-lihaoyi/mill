import $file.^.outer.build
import $file.inner.build

import mill._

def assertPaths(p1 : os.Path, p2 : os.Path) : Unit = if (p1 != p2) throw new Exception(
  s"Paths were not equal : \n- $p1 \n- $p2"
)

object sub extends PathAware with DestAware {

  object sub extends PathAware with DestAware

  object sub2 extends ^.outer.build.PathAware with ^.outer.build.DestAware

}

def checkProjectPaths = T {
  val thisPath : os.Path = millSourcePath
  assert(thisPath.last == "project")
  assertPaths(sub.selfPath(), thisPath / 'sub)
  assertPaths(sub.sub.selfPath(), thisPath / 'sub / 'sub)
  assertPaths(sub.sub2.selfPath(), thisPath / 'sub / 'sub2)
}

def checkInnerPaths = T {
  val thisPath : os.Path = millSourcePath
  assertPaths(inner.build.millSourcePath, thisPath / 'inner )
  assertPaths(inner.build.sub.selfPath(), thisPath / 'inner / 'sub)
  assertPaths(inner.build.sub.sub.selfPath(), thisPath / 'inner / 'sub / 'sub)
}

def checkOuterPaths = T {
  val thisPath : os.Path = millSourcePath
  assertPaths(^.outer.build.millSourcePath, thisPath / os.up / 'outer )
  assertPaths(^.outer.build.sub.selfPath(), thisPath / os.up / 'outer / 'sub)
  assertPaths(^.outer.build.sub.sub.selfPath(), thisPath / os.up / 'outer / 'sub / 'sub)
}

def checkOuterInnerPaths = T {
  val thisPath : os.Path = millSourcePath
  assertPaths(^.outer.inner.build.millSourcePath, thisPath / os.up / 'outer / 'inner )
  assertPaths(^.outer.inner.build.sub.selfPath(), thisPath / os.up / 'outer / 'inner /'sub)
  assertPaths(^.outer.inner.build.sub.sub.selfPath(), thisPath / os.up / 'outer / 'inner / 'sub / 'sub)
}

def checkProjectDests = T {
  val outPath : os.Path = millSourcePath / 'out
  assertPaths(sub.selfDest(), outPath / 'sub)
  assertPaths(sub.sub.selfDest(), outPath / 'sub / 'sub)
  assertPaths(sub.sub2.selfDest(), outPath / 'sub / 'sub2)
}

def checkInnerDests = T {
  val foreignOut : os.Path = millSourcePath / 'out / "foreign-modules"
  assertPaths(inner.build.sub.selfDest(), foreignOut / 'inner / 'sub)
  assertPaths(inner.build.sub.sub.selfDest(), foreignOut / 'inner / 'sub / 'sub)
}

def checkOuterDests = T {
  val foreignOut : os.Path = millSourcePath / 'out / "foreign-modules"
  assertPaths(^.outer.build.sub.selfDest(), foreignOut / "up-1" / 'outer/ 'sub )
  assertPaths(^.outer.build.sub.sub.selfDest(), foreignOut / "up-1" / 'outer/ 'sub / 'sub)
}

def checkOuterInnerDests = T {
  val foreignOut : os.Path = millSourcePath / 'out / "foreign-modules"
  assertPaths(^.outer.inner.build.sub.selfDest(), foreignOut / "up-1" / 'outer/ 'inner / 'sub)
  assertPaths(^.outer.inner.build.sub.sub.selfDest(), foreignOut / "up-1" / 'outer/ 'inner / 'sub / 'sub)
}


trait PathAware extends mill.Module {

  def selfPath = T { millSourcePath }
}

trait DestAware extends mill.Module {
  def selfDest = T { T.ctx().dest / os.up / os.up }
}

