import mill.Agg
import mill.scalalib._

def source = T.source{
  PathRef(os.pwd / "file.txt")
}

def source2 = T.source{
  PathRef(os.pwd / "file2.txt")
}

def concated = T{
  Thread.sleep(1000)
  os.read(source().path) + os.read(source2().path)
}
def split1 = T{
  Thread.sleep(1000)
  concated().take(concated().length / 2)
}
def split2 = T{
  Thread.sleep(1000)
  concated().drop(concated().length / 2)
}
def split3 = T{
  Thread.sleep(1000)
  concated().take(concated().length / 2)
}
def split4 = T{
  Thread.sleep(1000)
  concated().drop(concated().length / 2)
}
def join = T{
  Thread.sleep(1000)
  split2() + split1() + split4() + split3()
}