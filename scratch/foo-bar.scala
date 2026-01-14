def main() = {


  for(i <- Range(0, 1000000)) {
    Thread.sleep(1000)
    println("Foo " + i)
  }

}
