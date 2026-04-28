package mill.androidlib


trait AndroidLibScalaModule extends AndroidLibModule with AndroidScalaModule {

  trait AndroidLibScalaTests extends AndroidLibTests with AndroidScalaTestModule {}
}
