package hello;

abstract class Grandparent{
    public void doGrandThingConcrete(){
        doGrandThingAbstract();
    }
    abstract public void doGrandThingAbstract();
}

abstract class Parent extends Grandparent{
    public void doParentThing(){
        doGrandThingConcrete();
    }
}
