package util.taskblocks;

import org.tinylog.Logger;

import java.util.Locale;
import java.util.StringJoiner;

public class MetaBlock extends AbstractBlock{
    String id;
    String info;
    enum RUNTYPE {STEP,ONESHOT};
    RUNTYPE type = RUNTYPE.ONESHOT;
    TaskBlock failure;
    int runIndex=0;

    public MetaBlock( String id, String info){
        this.id=id;
        this.info=info;
    }
    public MetaBlock info(String info){
        this.info=info;
        return this;
    }
    public MetaBlock type( String runType){
        switch(runType.toLowerCase()){
            case "step": type=RUNTYPE.STEP; break;
            case "oneshot": case "": type=RUNTYPE.ONESHOT; break;
            default:
                Logger.error("Invalid runtype for "+id+" -> "+runType);
        }
        return this;
    }
    public MetaBlock failure( TaskBlock fail){
        this.failure=fail;
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
    public boolean start(TaskBlock starter) {
        if( starter instanceof TriggerBlock ){
            if( !((TriggerBlock) starter).isInterval())
                Logger.info("Starting "+id+ (info.isEmpty()?"":" that "+info));
        }else{
            Logger.info("Starting "+id+ (info.isEmpty()?"":" that "+info));
        }
        if( type==RUNTYPE.ONESHOT) {
            doNext();
        }else{
            next.get(runIndex++).start(this);
        }
        return true;
    }

    @Override
    public boolean stop() {
        Logger.info("Stopping "+id+ (info.isEmpty()?"":" that "+info));
        return super.stop();
    }

    @Override
    public void nextOk() {
        if( type==RUNTYPE.STEP && runIndex<next.size()){
            next.get(runIndex++).start(this);
        }
    }

    @Override
    public void nextFailed(TaskBlock failed) {
        Logger.error("Run of the list failed, start failure list?");
        if( next.size()==1){ // If only one branch, run failure?
            failure.start(this);
        }
        //next.forEach( TaskBlock::stop);
    }
    public String toString(){
        return "Metablock named "+id+(info.isEmpty()?"":" and info '"+info+"'");
    }
}
