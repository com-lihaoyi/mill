import ammonite.ops._
import mill.T
import mill.eval.JavaCompileJarTests.compileAll
import mill.eval.PathRef
import mill.modules.Jvm
import mill.util.Loose

def sourceRootPath = basePath / 'src
def resourceRootPath = basePath / 'resources

// sourceRoot -> allSources -> classFiles
//                                |
//                                v
//           resourceRoot ---->  jar
def sourceRoot = T.source{ sourceRootPath }
def resourceRoot = T.source{ resourceRootPath }
def allSources = T{ ls.rec(sourceRoot().path).map(PathRef(_)) }
def classFiles = T{ compileAll(allSources()) }
def jar = T{ Jvm.createJar(Loose.Agg(resourceRoot().path, classFiles().path)) }

def run(mainClsName: String) = T.command{
  %%('java, "-cp", classFiles().path, mainClsName)(T.ctx().dest)
}
