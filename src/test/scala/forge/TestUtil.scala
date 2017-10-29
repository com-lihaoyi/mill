package forge

import utest.assert

import scala.collection.mutable

object TestUtil {

  def checkTopological(targets: OSet[Target[_]]) = {
    val seen = mutable.Set.empty[Target[_]]
    for(t <- targets.items.reverseIterator){
      seen.add(t)
      for(upstream <- t.inputs){
        assert(!seen(upstream))
      }
    }
  }

}
