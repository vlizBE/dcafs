package util.taskblocks;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.TimeTools;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    /* Time */
    LocalTime time;
    ArrayList<DayOfWeek> triggerDays;
    boolean utc=false;

    public TriggerBlock( ScheduledExecutorService scheduler ){
        this.scheduler=scheduler;
    }
    public static TriggerBlock prepBlock( ScheduledExecutorService scheduler ){
        return new TriggerBlock(scheduler);
    }
    @Override
    public boolean start(){
        Logger.info("Trigger started!");

        if( time!=null ) {
            interval_ms = calcTimeDelaySeconds();
            Logger.info("Next time event: "+TimeTools.convertPeriodtoString(interval_ms,TimeUnit.SECONDS));
        }
        if(interval_ms==-1){
            Logger.error("Invalid interval time, not starting");
            return false;
        }
        if(tries==-1||tries>1){ // repeating infinite or finite
            future = scheduler.scheduleAtFixedRate(()->doNext(),delay_ms,interval_ms, TimeUnit.MILLISECONDS);
            return true;
        }else if(tries==1){ // one shot
            if( interval_ms==0) {
                next.forEach( n -> scheduler.submit(()->n.start()));
            }else {
                scheduler.schedule(() -> doNext(), interval_ms, time==null?TimeUnit.MILLISECONDS:TimeUnit.SECONDS);
            }
            return true;
        }
        return false;
    }

    @Override
    public void nextOk() {
        if( tries > 1){

        }
    }

    @Override
    public void nextFailed() {
        if(future==null)
            return;

        if( tries >= 1){
            tries--;
            Logger.warn("Check failed, "+tries+" retries left");
        }
        if( tries <= 0 ) {
            future.cancel(false);
            Logger.error("Successive check failed, cancelling.");
        }
    }

    public void doNext(){
        next.forEach( n -> scheduler.submit(()->n.start()));

        if( time!=null )
            start();
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
            case "time":  // Has a timestamp and a days of week option
            case "utctime":
                utc=true;
            case "localtime":
                time = LocalTime.parse( values[0], DateTimeFormatter.ISO_LOCAL_TIME );
                triggerDays = TimeTools.convertDAY(values.length==2?values[1]:"");
                tries=1;
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
        parentBlock = Optional.ofNullable(prev);
        parentBlock.ifPresentOrElse( tb->tb.addNext(this), ()->srcBlock=true);

        return Optional.of(this);
    }
    private long calcTimeDelaySeconds(){
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if( !utc )
            now = LocalDateTime.now();

        LocalDateTime triggerTime = now.with(time).plusNanos(now.getNano()); // Use the time of the task

        if( triggerTime.isBefore(now.plusNanos(100000)) ) // If already happened today
            triggerTime=triggerTime.plusDays(1);

        if( triggerDays.isEmpty()){ // If the list of days is empty
            return -1;
        }
        int x=0;
        while( !triggerDays.contains(triggerTime.getDayOfWeek()) ){
            triggerTime=triggerTime.plusDays(1);
            x++;
            if( x > 8 ) //shouldn't be possible, just to be sure
                return -1;
        }
        return Duration.between( now, triggerTime ).getSeconds();
    }
}
