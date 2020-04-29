import mill.Agg
import mill.api.PathRef
import mill.scalalib._

def source = T.input {
  os.pwd / "file.txt"
}

def command() = T.command{
  if (os.exists(source())) {
    println(os.read(source()))
  }else{
    println("<no file found>")
  }
}