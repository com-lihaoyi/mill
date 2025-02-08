package hello;

abstract class Grandparent {
  public void doGrandThingConcrete() {
    doGrandThingAbstract();
  }

  public abstract void doGrandThingAbstract();
}

abstract class Parent extends Grandparent {
  public void doParentThing() {
    doGrandThingConcrete();
  }
}
