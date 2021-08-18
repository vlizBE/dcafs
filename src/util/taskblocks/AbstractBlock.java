package util.taskblocks;

import org.tinylog.Logger;
import util.data.NumericVal;
import util.task.Task;

import java.util.ArrayList;
import java.util.Optional;
import java.util.StringJoiner;

public abstract class AbstractBlock implements TaskBlock{
    ArrayList<NumericVal> sharedMem;
    ArrayList<TaskBlock> next = new ArrayList<>();
    String ori;
    Optional<TaskBlock> parentBlock=Optional.empty();
    boolean stopFuture=false;

    boolean srcBlock=false;
    boolean valid=true;

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
        if( !next.isEmpty() ){
            if( mergeCmdBlock(block) ) {
                return true;
            }
        }
        next.add(block);
        return true;
    }
    public void doNext() {
        if( stopFuture ){
            stopFuture=false;
            return;
        }
        next.forEach( TaskBlock::start);
    }
    private boolean mergeCmdBlock( TaskBlock add){
        if( add instanceof CmdBlock ){
            var oriOpt = next.stream().filter( t -> t instanceof CmdBlock).findFirst()
                    .map(t -> Optional.of((CmdBlock)t) ).orElse(Optional.empty());
            return oriOpt.map( ori ->{
                ((CmdBlock) add).getCmds().forEach( ori::addCmd);
                return true;
            }).orElse(false);
        }
        return false;
    }

    public boolean addData(String data){
        return true;
    }

    @Override
    public void getBlockInfo(StringJoiner join,String offset) {
        var list = ("-> "+toString()).split("\r\n");
        for( var s:list)
            join.add(offset + s);
        for( var b : next ){
            b.getBlockInfo(join,offset+"  ");
        }
    }

    public boolean stop(){
        next.forEach(TaskBlock::stop);
        return true;
    }
}

