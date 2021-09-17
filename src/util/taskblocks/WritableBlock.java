package util.taskblocks;

import io.Writable;
import io.collector.CollectorFuture;
import io.collector.ConfirmCollector;
import io.stream.BaseStream;
import org.tinylog.Logger;
import util.data.DataProviding;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

public class WritableBlock extends AbstractBlock implements CollectorFuture {

    Writable wr;
    String reply;
    String data;
    ConfirmCollector cc;
    BaseStream target;
    DataProviding dp;
    boolean hasRT=false;
    String delimiter="";

    public WritableBlock(Writable wr, DataProviding dp, String set){
        this.wr=wr;
        this.ori=set;
        this.dp=dp;
      //  ori = wr.getID()+"->"+set;
    }
    public WritableBlock(BaseStream target,DataProviding dp, String set){
        this( (Writable) target,dp,set);
    }
    public static WritableBlock prepBlock( Writable wr,DataProviding dp, String data ){
        return new WritableBlock(wr,dp,data);
    }
    public static WritableBlock prepBlock( BaseStream target, DataProviding dp, String data ){
        return new WritableBlock(target,dp,data);
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
    public void addDelimiter( String deli ){
        delimiter=deli;
    }
    @Override
    public boolean build() {
        if( dp != null ) {
            if (sharedMem == null) // If it didn't receive a shared Mem
                sharedMem = new ArrayList<>(); // make it
            data = dp.buildNumericalMem(ori, sharedMem, 0);
            if( sharedMem.isEmpty()) { // Remove the reference if it remained empty
                sharedMem = null;
            }else{
                hasRT=true;
            }
        }else{
            Logger.warn("No dp, skipping numerical mem");
        }
        return true;
    }
    public boolean addData(String data){
        return wr.writeLine(data);
    }
    @Override
    public boolean start(TaskBlock starter) {
        try {
            String send=data;
            if( hasRT ){
                for( int a=sharedMem.size()-1;a>=0;a--)
                    send = send.replace("i"+a,""+sharedMem.get(a).value());
                send = dp.parseRTline(send,"???");
            }
            if (!send.isEmpty()) {
                if (cc != null) {
                    if( !delimiter.isEmpty()){
                        for (var p : send.split(delimiter))
                            cc.addConfirm(p, reply);
                    }else{
                        cc.addConfirm(send,reply);
                    }
                    target.addTarget(cc);
                } else {
                    if (wr.writeLine(send)) {
                        doNext();
                    } else {
                        parentBlock.filter(pb -> pb instanceof TriggerBlock).ifPresent(pb -> pb.nextFailed(this));
                        return false;
                    }
                }
                parentBlock.filter(pb -> pb instanceof TriggerBlock).ifPresent(TaskBlock::nextOk);// needs to know because of while/waitfor
                return true;
            }
            parentBlock.filter(pb -> pb instanceof TriggerBlock).ifPresent(pb -> pb.nextFailed(this));
        }catch (Exception e){
            Logger.error(e);
        }
        return false;
    }
    @Override
    public void collectorFinished(String id, String message, Object result) {
        boolean res= (boolean) result;
        if( !res ){
            Logger.error("Reply send failed (tasksetid_streamid) -> "+id);
            // run failure?
            parentBlock.get().nextFailed(this);
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
