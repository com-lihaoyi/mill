import mill.T
import mill.eval.JavaCompileJarTests.compileAll
import mill.api.PathRef
import mill.modules.Jvm
import mill.api.Loose
import mill.modules.Jvm.createManifest

def sourceRootPath = millSourcePath / 'src
def resourceRootPath = millSourcePath / 'resources

// sourceRoot -> allSources -> classFiles
//                                |
//                                v
//           resourceRoot ---->  jar
def sourceRoot = T.sources{ sourceRootPath }
def resourceRoot = T.sources{ resourceRootPath }
def allSources = T{ sourceRoot().flatMap(p => os.walk(p.path)).map(PathRef(_)) }
def classFiles = T{ compileAll(allSources()) }
def jar = T{ Jvm.createJar(Loose.Agg(classFiles().path) ++ resourceRoot().map(_.path), createManifest(None)) }

def run(mainClsName: String) = T.command{
  os.proc('java, "-cp", classFiles().path, mainClsName).call(T.ctx().dest)
}
