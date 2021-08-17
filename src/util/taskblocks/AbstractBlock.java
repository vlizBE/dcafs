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

    /* Hierarchy */
    public TaskBlock link( TaskBlock parent ){
        parentBlock = Optional.ofNullable(parent);
        parentBlock.ifPresentOrElse( tb->tb.addNext(this), ()->srcBlock=true);

        if( srcBlock ){
            sharedMem = new ArrayList<>();
        }else{
            sharedMem = parent.getSharedMem();
        }
        return this;
    }
    public Optional<TaskBlock> getParent(){
        return parentBlock;
    }
    public Optional<TaskBlock> getSourceBlock(){
        if( srcBlock )
            return Optional.of(this);
        return parentBlock.map(pb->pb.getSourceBlock()).orElse(Optional.empty());
    }
    public boolean addNext(TaskBlock block) {
        next.add(block);
        return true;
    }
    public void doNext() {
        next.forEach( TaskBlock::start);
    }
    public boolean addData(String data){
        return true;
    }
}
