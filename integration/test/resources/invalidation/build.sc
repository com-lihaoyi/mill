import $file.a.inputA
import $file.b.inputB
import $file.inputC
import $ivy.`org.scalaj::scalaj-http:2.4.2`

def task = T {
  inputA.input()
  inputB.input()
  inputC.input()
}
