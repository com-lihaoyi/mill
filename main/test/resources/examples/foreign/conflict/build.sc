import $file.inner.{build => innerBuild}
import mill._

// In this build, we have a local module targeting
// the 'inner sub-directory, and an imported foreign
// module in that same directory. Their sourcePaths
// should be the same, but their dest paths should
// be different to avoid both modules over-writing
// each other's caches .

def checkPaths : T[Unit] = T {
  if (innerBuild.millSourcePath != inner.millSourcePath)
    throw new Exception("Source paths should be the same")
}

def checkDests : T[Unit] = T {
  if (innerBuild.selfDest == inner.selfDest)
    throw new Exception("Dest paths should be different")
}

object inner extends mill.Module {
  def selfDest = T { T.ctx().dest / os.up / os.up }
}
