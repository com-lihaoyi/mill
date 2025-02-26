package mill.define
private[mill] class Plan(
                          val transitive: IndexedSeq[Task[?]],
                          val sortedGroups: MultiBiMap[Task[?], Task[?]]
                        )
