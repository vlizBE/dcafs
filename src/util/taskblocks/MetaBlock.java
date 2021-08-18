package util.taskblocks;

import org.tinylog.Logger;

import java.util.StringJoiner;

public class MetaBlock extends AbstractBlock{
    String id;
    String info;

    public MetaBlock( String id, String info){
        this.id=id;
        this.info=info;
    }
    public TaskBlock info(String info){
        this.info=info;
        return this;
    }
    public String getTreeInfo(){
        StringJoiner join = new StringJoiner("\r\n","\r\n==> ","\r\n--------------------------------------");
        join.add( toString() );
        for( var b : next ){
            b.getBlockInfo(join,"  ");
        }
        return join.toString();
    }
    @Override
    public boolean build() {
        return true;
    }

    @Override
    public boolean start() {
        Logger.info("Starting "+id+ (info.isEmpty()?"":" that "+info));
        doNext();
        return true;
    }

    @Override
    public boolean stop() {
        Logger.info("Stopping "+id+ (info.isEmpty()?"":" that "+info));
        return super.stop();
    }

    @Override
    public void nextOk() {

    }

    @Override
    public void nextFailed() {

    }
    public String toString(){
        return "Metablock named "+id+(info.isEmpty()?"":" and info '"+info+"'");
    }
}
