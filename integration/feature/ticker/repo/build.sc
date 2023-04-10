import mill._

object print extends PrintModule{
  def print0(p: java.io.PrintStream, s: String) = p.print(s)
}
object println extends PrintModule{
  def print0(p: java.io.PrintStream, s: String) = p.println(s)
}

trait PrintModule extends Module{
  def print0(p: java.io.PrintStream, s: String)

  def systemOut1 = T {
    System.out.println("hello1")
    "hello"
  }

  def systemErr1 = T {
    System.err.println("world1")
    systemOut1()
  }

  def consoleOut1 = T {
    Console.out.println("iam1")
    systemErr1()
  }

  def consoleErr1 = T {
    Console.err.println("cow1")
    consoleOut1()
  }

  def noPrint1 = T {
    consoleErr1()
  }

  def systemOut2 = T {
    System.out.println("hello2")
    noPrint1()
  }

  def systemErr2 = T {
    System.err.println("world2")
    systemOut2()
  }

  def consoleOut2 = T {
    Console.out.println("iam2")
    systemErr2()
  }

  def consoleErr2 = T {
    Console.err.println("cow2")
    consoleOut2()
  }

  def noPrint2 = T {
    consoleErr2()
  }

  def end = T {
    noPrint2()
  }
}

