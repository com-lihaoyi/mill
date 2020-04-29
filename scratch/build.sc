import mill.Agg
import mill.scalalib._

def source = T.source{
  PathRef(os.pwd / "file.txt")
}

def command() = T.command{
  if (os.exists(source().path)) {
    println(os.read(source().path))
  }else{
    println("<no file found>")
  }
}