Benchmarks
====
* [Scanner benchmarks](src/main/scala/better/files/Scanners.scala):
```
> sbt "benchmarks/test"
JavaScanner              : 2191 ms
StringBuilderScanner     : 1325 ms
CharBufferScanner        : 1117 ms
StreamingScanner         :  212 ms
IterableScanner          :  365 ms
IteratorScanner          :  297 ms
BetterFilesScanner       :  272 ms
ArrayBufferScanner       :  220 ms
FastJavaIOScanner2       :  181 ms
FastJavaIOScanner        :  179 ms
```

----

[![YourKit](https://www.yourkit.com/images/yklogo.png)](https://www.yourkit.com/)

YourKit supports better-files with its full-featured Java Profiler. 
YourKit, LLC is the creator of [YourKit Java Profiler](https://www.yourkit.com/java/profiler/) and [YourKit .NET Profiler](https://www.yourkit.com/.net/profiler/), 
innovative and intelligent tools for profiling Java and .NET applications.
