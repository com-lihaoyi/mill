import mill._

def input = Task {
  println("e")
  build.a.input()
}
