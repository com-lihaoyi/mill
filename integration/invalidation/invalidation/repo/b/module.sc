import mill._
import $file.inputD

def input = task {
  inputD.method()
  println("b")
}
