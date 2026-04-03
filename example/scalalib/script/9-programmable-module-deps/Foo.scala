//| moduleDeps: [bar]

def main(args: Array[String]) = {
  println(bar.Bar.generateHtml(args(0)))
}
