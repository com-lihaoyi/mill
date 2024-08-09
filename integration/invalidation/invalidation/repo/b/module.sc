import mill._
import $file.inputD

def input = Task {
  inputD.method()
  println("b")
}
