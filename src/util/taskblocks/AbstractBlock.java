package util.taskblocks;

import util.data.NumericVal;

import java.util.ArrayList;
import java.util.Optional;

public abstract class AbstractBlock implements TaskBlock{
    ArrayList<NumericVal> sharedMem;
    ArrayList<TaskBlock> next = new ArrayList<>();
    String ori;
    Optional<TaskBlock> parentBlock=Optional.empty();

    boolean srcBlock=false;

    public void setSharedMem(ArrayList<NumericVal> mem) {
        sharedMem=mem;
    }
    public boolean hasSharedMem(){
        return sharedMem!=null;
    }
    public ArrayList<NumericVal> getSharedMem(){
        return sharedMem;
    }
    abstract void doNext();

    public Optional<TaskBlock> getSourceBlock(){
        if( srcBlock )
            return Optional.of(this);
        return parentBlock.map(pb->pb.getSourceBlock()).orElse(Optional.empty());
    }

}
