package util.taskblocks;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.TimeTools;

import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TriggerBlock extends AbstractBlock{

    long delay_ms =0;
    long interval_ms =1000;
    int tries=-1;

    ScheduledExecutorService scheduler;
    ScheduledFuture future;

    public TriggerBlock( ScheduledExecutorService scheduler ){
        this.scheduler=scheduler;
    }
    public static TriggerBlock prepBlock( ScheduledExecutorService scheduler ){
        return new TriggerBlock(scheduler);
    }
    @Override
    public boolean start(){
        if(tries==-1||tries>1){ // repeating infinite or finite
            future = scheduler.scheduleAtFixedRate(()->doNext(),delay_ms,interval_ms, TimeUnit.MILLISECONDS);
            return true;
        }else if(tries==1){ // one shot
            if( interval_ms==0) {
                next.forEach( n -> scheduler.submit(()->n.start()));
            }else {
                scheduler.schedule(() -> doNext(), interval_ms, TimeUnit.MILLISECONDS);
            }
            return true;
        }
        return false;
    }

    @Override
    public void nextOk() {

    }

    @Override
    public void nextFailed() {

    }

    public void doNext(){
        if( tries > 0){
            tries--;
        }
        next.forEach( n -> scheduler.submit(()->n.start()));
        future.cancel(false);
    }
    public void addNext(TaskBlock block) {
        next.add(block);
    }
    @Override
    public Optional<TaskBlock> build(TaskBlock prev, String set) {
        ori=set;
        if( !set.contains(":"))
            return Optional.empty();

        String type = set.substring(0,set.indexOf(":"));
        String value = set.substring(set.indexOf(":")+1);
        var values = value.split(",");

        switch(type){ //actually all are the same, just different kind of repeat
            case "time": // Has a timestamp and a days of week option
                break;
            case "delay": // Has a delay
                interval_ms = TimeTools.parsePeriodStringToMillis(value);
                tries=1;
                break;
            case "interval": // Has an optional initial delay and an interval
                delay_ms = TimeTools.parsePeriodStringToMillis(values[0]);
                if( values.length==2){
                    interval_ms = TimeTools.parsePeriodStringToMillis(values[1]);
                }else{
                    interval_ms = delay_ms;
                }
                tries=-1;
                break;
            case "retry": // Has an interval and an amount of attempts
                interval_ms = TimeTools.parsePeriodStringToMillis(values[0]);
                tries= values.length==2?NumberUtils.toInt(values[1],-1):-1;
                break;
            default:
                Logger.error("No such type: "+type);
                return Optional.empty();
        }
        if( prev!=null) {
            prev.addNext(this);
            parentBlock=prev;
        }else{
            srcBlock=true;
        }
        return Optional.of(this);
    }
}
