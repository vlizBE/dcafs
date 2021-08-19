package util.taskblocks;

import io.Writable;
import io.collector.CollectorFuture;
import io.collector.ConfirmCollector;
import io.stream.BaseStream;
import org.tinylog.Logger;

import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

public class WritableBlock extends AbstractBlock implements CollectorFuture {

    Writable wr;
    String reply;
    String data;
    ConfirmCollector cc;
    BaseStream target;

    public WritableBlock(Writable wr, String set){
        this.wr=wr;
        this.data=set;
        ori = wr.getID()+"->"+set;
    }
    public WritableBlock(BaseStream target, String set){
        this.target=target;
        wr = (Writable) target;
        this.data=set;
    }
    public static WritableBlock prepBlock(Writable wr, String data){
        return new WritableBlock(wr,data);
    }
    public static WritableBlock prepBlock( BaseStream target, String data){
        return new WritableBlock(target,data);
    }
    public Optional<Writable> addReply(String reply, ScheduledExecutorService scheduler){
        if( !reply.isEmpty() && target!=null ){
            this.reply=reply;
            cc = new ConfirmCollector("test",3,5,wr,scheduler);
            cc.addListener(this);
            return Optional.of(cc);
        }
        return Optional.empty();
    }
    @Override
    public boolean build() {
        return true;
    }

    @Override
    public boolean start(TaskBlock starter) {
        if( !data.isEmpty()){
            if( cc!=null){
                for( var p : data.split(";"))
                    cc.addConfirm(p,reply);
                target.addTarget(cc);
            }else {
                if( wr.writeLine(data) ) {
                    doNext();
                }else{
                    if( parentBlock.get() instanceof TriggerBlock ) // needs to know because of while/waitfor
                        parentBlock.get().nextFailed();
                    return false;
                }
            }
            if( parentBlock.get() instanceof TriggerBlock ) // needs to know because of while/waitfor
                parentBlock.get().nextOk();
            return true;
        }

        if( parentBlock.get() instanceof TriggerBlock ) // needs to know because of while/waitfor
            parentBlock.get().nextFailed();

        return false;
    }
    @Override
    public void collectorFinished(String id, String message, Object result) {
        boolean res= (boolean) result;
        if( !res ){
            Logger.error("Reply send failed (tasksetid_streamid) -> "+id);
            // run failure?
            parentBlock.get().nextFailed();
        }else{
            doNext();
        }
    }
    public String toString(){
        if( cc!=null ){
            return "Write '"+data+"' to the writable "+wr.getID()+" and expect '"+reply+"' as reply";
        }
        return "Write '"+data+"' to the writable "+wr.getID();
    }
}
