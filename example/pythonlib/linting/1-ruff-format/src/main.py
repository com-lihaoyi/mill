from typing import Self
class IntWrapper:
   def __init__(self, x:int):
     self.x    =x
   def plus(self, w:Self) ->   Self:
      return     IntWrapper(self.x + w.x)
print(IntWrapper(2).plus(IntWrapper(3)).x)
