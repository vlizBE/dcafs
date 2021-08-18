package util.taskblocks;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.TimeTools;

import java.sql.Time;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TriggerBlock extends AbstractBlock{

    long delay_ms =0;
    long interval_ms =1000;
    int tries=-1;

    enum TYPE {CLOCK, WHILE, WAITFOR, OTHER, RETRY,INTERVAL,DELAY};

    TYPE trigType =TYPE.OTHER;

    ScheduledExecutorService scheduler;
    ScheduledFuture future;

    /* Time */
    LocalTime time;
    ArrayList<DayOfWeek> triggerDays;
    boolean utc=false;

    public TriggerBlock( ScheduledExecutorService scheduler, String set ){
        this.scheduler=scheduler;
        ori=set;
    }
    public static TriggerBlock prepBlock( ScheduledExecutorService scheduler, String set ){
        return new TriggerBlock(scheduler,set);
    }
    @Override
    public boolean build( ) {

        if( !ori.contains(":"))
            return false;

        String type = ori.substring(0,ori.indexOf(":"));
        String value = ori.substring(ori.indexOf(":")+1);
        var values = value.split(",");

        switch(type){ //actually all are the same, just different kind of repeat
            case "time":  // Has a timestamp and a days of week option
            case "utctime":
                utc=true;
            case "localtime":
                time = LocalTime.parse( values[0], DateTimeFormatter.ISO_LOCAL_TIME );
                triggerDays = TimeTools.convertDAY(values.length==2?values[1]:"");
                tries=1;
                this.trigType =TYPE.CLOCK;
                break;
            case "delay": // Has a delay
                interval_ms = TimeTools.parsePeriodStringToMillis(value);
                tries=1;
                trigType=TYPE.DELAY;
                break;
            case "interval": // Has an optional initial delay and an interval
                delay_ms = TimeTools.parsePeriodStringToMillis(values[0]);
                if( values.length==2){
                    interval_ms = TimeTools.parsePeriodStringToMillis(values[1]);
                }else{
                    interval_ms = delay_ms;
                }
                tries=-1;
                trigType=TYPE.INTERVAL;
                break;
            case "retry": // Has an interval and an amount of attempts
                interval_ms = TimeTools.parsePeriodStringToMillis(values[0]);
                tries= values.length==2?NumberUtils.toInt(values[1],-1):-1;
                trigType =TYPE.RETRY;
                break;
            case "waitfor":
                interval_ms = TimeTools.parsePeriodStringToMillis(values[0]);
                tries= values.length==2?NumberUtils.toInt(values[1],-1):-1;
                trigType=TYPE.WAITFOR;
                break;
            case "while":
                interval_ms = TimeTools.parsePeriodStringToMillis(values[0]);
                tries= values.length==2?NumberUtils.toInt(values[1],-1):-1;
                trigType=TYPE.WHILE;
                break;
            default:
                Logger.error("No such type: "+type);
                return false;
        }
        return true;
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
    public boolean stop(){
        future.cancel(true);
        next.forEach(TaskBlock::stop);
        return true;
    }
    @Override
    public void nextOk() {

        switch( trigType){
            case WAITFOR: // Was ok so can stop waiting
                Logger.info("Go what was waiting for, cancelling");
                break;
            case WHILE:   // negated so this is actually failed
                Logger.info("OK = Negated negative so cancelling");
                break;
            case RETRY: // Got an ok within the amount of retries
                Logger.info("Retry successful");
                break;
        }
        future.cancel(true);
    }

    @Override
    public void nextFailed() {
        if(future==null) // can't do anything without a future
            return;

        switch(trigType){
            case WAITFOR:
                if( tries == -1) // if infinite tries, keep going
                    return;
                break;
        }
        if( tries >= 1){
            tries--;
            Logger.warn("Check failed, "+tries+" retries left");
        }
        if( tries==1 && trigType==TYPE.WHILE){ // If there's one try left
             var tb = next.get(0);
             if( tb instanceof CheckBlock && trigType==TYPE.WHILE ){ // negate the results of a CheckBlock
                ((CheckBlock)tb).setNegate(false);
                Logger.info("One check left, undoing the negate");
             }
        }
        if( tries <= 0 ) {
            future.cancel(false);
            Logger.error("Successive check failed, cancelling.");
        }
    }

    @Override
    public void doNext(){
        next.forEach( n -> scheduler.submit(()->n.start()));

        if( time!=null )
            start();
    }
    @Override
    public boolean addNext(TaskBlock block) {
        if( next.size()==1 && (trigType==TYPE.WAITFOR || trigType==TYPE.WHILE) ){
            Logger.error("Tried to add more than one block to a waitfor/while");
            return false;
        }
        next.add(block);

        if( block instanceof CheckBlock && trigType==TYPE.WHILE ){ // negate the results of a CheckBlock
            ((CheckBlock)block).setNegate(true);
        }
        return true;
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
    public String toString(){
        String interval = TimeTools.convertPeriodtoString(interval_ms,TimeUnit.MILLISECONDS);
        String delay = TimeTools.convertPeriodtoString(delay_ms,TimeUnit.MILLISECONDS);
        delay = "which, after a delay of "+delay+", starts";
        switch(trigType){
            case CLOCK: return "Clock Trigger scheduled for "+ori.split(":")[1];
            case WHILE: return "While trigger with an interval of "+interval+" and needs "+tries+" checks succeeded";
            case WAITFOR: return "Waitfor trigger with an interval of "+interval+" and "+(tries==-1?"infinite":tries)+" retries";
            case OTHER:
                break;
            case RETRY:
                break;
            case INTERVAL: return "Interval trigger "+(delay_ms==0?"with":delay)+" an interval of "+interval;
            case DELAY: return "Delayed trigger of "+interval;

        }
        return "triggr todo";
    }
    public boolean equals( TriggerBlock tb){
        return ori.equalsIgnoreCase(tb.ori);
    }
}
