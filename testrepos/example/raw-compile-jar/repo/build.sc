import mill._
import mill.modules.Jvm

def sourceRootPath = millSourcePath / "src"
def resourceRootPath = millSourcePath / "resources"

// sourceRoot -> allSources -> classFiles
//                                |
//                                v
//           resourceRoot ---->  jar
def sourceRoot = T.sources { sourceRootPath }
def resourceRoot = T.sources { resourceRootPath }
def allSources = T { sourceRoot().flatMap(p => os.walk(p.path)).map(PathRef(_)) }
def classFiles = T {
  os.makeDir.all(T.dest)

  os.proc("javac", allSources().map(_.path), "-d", T.dest).call(T.dest)
  mill.api.PathRef(T.dest)
}
def jar = T { Jvm.createJar(Agg(classFiles().path) ++ resourceRoot().map(_.path)) }

def run(mainClsName: String) = T.command {
  os.proc("java", "-cp", classFiles().path, mainClsName).call(T.dest)
}
