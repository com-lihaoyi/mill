package hello;

interface Grandparent{
    default void doGrandThingConcrete(){
        System.out.println("Running doGrandThingConcrete");
    }
    void doGrandThing();
    void otherNonSamMethod();
}
interface Parent extends Grandparent{
    default void doParentThingConcrete(){
        System.out.println("Running doGrandThingConcrete");
    }
    void doParentThing();
    void otherNonSamMethod();
}
