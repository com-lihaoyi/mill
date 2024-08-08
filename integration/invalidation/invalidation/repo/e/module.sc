import mill._

def input = task {
  println("e")
  build.a.input()
}
