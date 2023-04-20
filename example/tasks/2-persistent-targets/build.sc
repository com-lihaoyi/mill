import mill._, scalalib._
import java.util.Arrays, java.io.ByteArrayOutputStream, java.util.zip.GZIPOutputStream

def data = T.source(millSourcePath / "data")

def compressedData = T.persistent{
  println("Evaluating compressedData")
  os.makeDir.all(T.dest / "cache")
  os.remove.all(T.dest / "compressed")

  for(p <- os.list(data().path)){
    val compressedPath = T.dest / "compressed" / p.last
    val bytes = os.read.bytes(p)
    val hash = Arrays.hashCode(bytes)
    val cachedPath = T.dest / "cache" / hash.toHexString
    if (!os.exists(cachedPath)) {
      println("Compressing: " + p.last)
      os.write(cachedPath, compressBytes(bytes))
    } else {
      println("Reading Cached from disk: " + p.last)
    }
    os.copy(cachedPath, compressedPath / os.up / s"${p.last}.gz", createFolders = true)
  }

  os.list(T.dest / "compressed").map(PathRef(_))
}

def compressBytes(input: Array[Byte]) = {
  val bos = new ByteArrayOutputStream(input.length)
  val gzip = new GZIPOutputStream(bos)
  gzip.write(input)
  gzip.close()
  bos.toByteArray
}

// Persistent targets defined using `T.persistent` are similar to normal
// `Target`s, except their `T.dest` folder is not cleared before every
// evaluation. This makes them useful for caching things on disk in a more
// fine-grained manner than Mill's own Target-level caching.
//
// In this example, we implement a `compressedData` target that takes a folder
// of files in `inputData` and compresses them, while maintaining a cache of
// compressed contents for each file. That means that if the `inputData` folder
// is modified, but some files remain unchanged, those files would not be
// unnecessarily re-compressed when `compressedData` evaluates.
//
// Since persistent targets have long-lived state on disk that lives beyond a
// single evaluation, this raises the possibility of the disk contents getting
// into a bad state and causing all future evaluations to fail. It is left up
// to the person implementing the `T.persistent` to ensure their implementation
// is eventually consistent. You can also use `mill clean` to manually purge
// the disk contents to start fresh.

/** Usage

> ./mill show compressedData
Evaluating compressedData
Compressing: hello.txt
Compressing: world.txt
hello.txt.gz
world.txt.gz

> ./mill compressedData # when no input changes, compressedData does not evaluate at all

> sed -i 's/Hello/HELLO/g' data/hello.txt

> ./mill compressedData # when one input file changes, only that file is re-compressed
Compressing: hello.txt
Cached from disk: world.txt

> ./mill clean compressedData

> ./mill compressedData
Evaluating compressedData
Compressing: hello.txt
Compressing: world.txt

*/
