import mill._
import $file.^.a.inputA

def input = T {
  println("e")
  inputA.input()
}
