package util.taskblocks;

import das.CommandPool;
import io.Writable;

import java.util.ArrayList;
import java.util.Optional;
import java.util.StringJoiner;

public class CmdBlock extends AbstractBlock{

    CommandPool cmdPool;
    ArrayList<String> cmds =new ArrayList<>();

    public CmdBlock( CommandPool cmdPool, String set ){
        this.cmdPool=cmdPool;
        cmds.add(set);
    }
    public static CmdBlock prepBlock( CommandPool cmdPool, String set ){
        return new CmdBlock(cmdPool,set);
    }
    public CmdBlock addCmd( String cmd){
        cmds.add(cmd);
        return this;
    }
    public CmdBlock addCmd( Writable wr, String cmd){
        cmds.add(cmd);
        return this;
    }

    public ArrayList<String> getCmds(){
        return cmds;
    }
    @Override
    public boolean build() {
        return true;
    }
    public boolean addData( String data ){
        var ans = cmdPool.createResponse(data, null, false);
        next.forEach( n -> n.addData(ans));
        return true;
    }
    @Override
    public boolean start(TaskBlock starter) {
        for( var cmd : cmds) {
           var ans = cmdPool.createResponse(cmd, null, false);
           next.forEach( n -> n.addData(ans));
        }
        doNext();
        return true;
    }
    @Override
    public boolean addNext(TaskBlock block) {
        if( !next.isEmpty() ){
            if( mergeCmdBlock(block) ) {
                return true;
            }
        }
        next.add(block);
        return true;
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
    public String toString(){
        if( cmds.size()==1)
            return "Execute cmd: "+cmds.get(0);
        StringJoiner join = new StringJoiner("\r\n","Execute "+cmds.size()+" cmds:","");
        join.add("");
        cmds.forEach( c -> join.add("     > "+c));
        return join.toString();
    }
}
