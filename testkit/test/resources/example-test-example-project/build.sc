import mill._

def testSource = T.source(millSourcePath / "source-file.txt")
def testTask = T { os.read(testSource().path).toUpperCase() }

/** Usage

> ./mill testTask
...compiling 1 Scala source...

> cat out/testTask.json
..."HELLO WORLD SOURCE FILE"...

> sed -i.bak 's/file/file!!!/g' source-file.txt

> ./mill testTask

> cat out/testTask.json
..."HELLO WORLD SOURCE FILE!!!"...

*/