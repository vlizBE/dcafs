package util.taskblocks;

import das.CommandPool;
import io.Writable;

import java.util.ArrayList;

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
    public boolean start() {
        for( var cmd : cmds) {
           var ans = cmdPool.createResponse(cmd, null, false);
           next.forEach( n -> n.addData(ans));
        }
        doNext();
        return true;
    }

    @Override
    public void nextOk() {

    }

    @Override
    public void nextFailed() {

    }
}
