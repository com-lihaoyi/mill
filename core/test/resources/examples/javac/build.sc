import ammonite.ops._
import mill.T
import mill.eval.JavaCompileJarTests.compileAll
import mill.eval.PathRef
import mill.modules.Jvm
import mill.util.Loose

def sourceRootPath = millSourcePath / 'src
def resourceRootPath = millSourcePath / 'resources

// sourceRoot -> allSources -> classFiles
//                                |
//                                v
//           resourceRoot ---->  jar
def sourceRoot = T.sources{ sourceRootPath }
def resourceRoot = T.sources{ resourceRootPath }
def allSources = T{ sourceRoot().flatMap(p => ls.rec(p.path)).map(PathRef(_)) }
def classFiles = T{ compileAll(allSources()) }
def jar = T{ Jvm.createJar(Loose.Agg(classFiles().path) ++ resourceRoot().map(_.path)) }

def run(mainClsName: String) = T.command{
  %%('java, "-cp", classFiles().path, mainClsName)(T.ctx().dest)
}
