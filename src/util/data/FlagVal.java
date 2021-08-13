package util.data;

import util.tools.Tools;
import worker.Datagram;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;

public class FlagVal {

    String group="";
    String name="";

    boolean state=false;
    boolean defState=false;

    /* Keep Time */
    long timestamp;
    boolean keepTime=false;

    BlockingQueue<Datagram> dQueue;

    public FlagVal(){}

    public FlagVal(boolean val){
        setState(val);
    }

    public static FlagVal newVal(String group, String name){
        return new FlagVal().group(group).name(name);
    }
    public static FlagVal newVal(String combined){
        String[] spl = combined.split("_");
        if( spl.length==2) {
            return new FlagVal().group(spl[0]).name(spl[1]);
        }else if( spl.length>2){
            String name = spl[1]+"_"+spl[2];
            for( int a=3;a<spl.length;a++)
                name+="_"+spl[a];
            return new FlagVal().group(spl[0]).name(name);
        }
        return new FlagVal().name(spl[0]);
    }

    public FlagVal defState( boolean defState){
        this.defState = defState;
        state=defState;
        return this;
    }
    public String getGroup(){
        return group;
    }
    public String getName(){
        return name;
    }
    public String getID(){
        return group.isEmpty()?name:(group+"_"+name);
    }
    public FlagVal setState( boolean val){

        /* Keep time of last value */
        if( keepTime )
            timestamp= Instant.now().toEpochMilli();
        state=val;
        return this;
    }
    public void raise(){
        state=true;
    }
    public void lower(){
        state=false;
    }
    public boolean isUp(){
        return state;
    }
    public boolean isDown(){
        return !state;
    }
    public int getValue(){
        return state?1:0;
    }
    public String toString(){
        return ""+state;
    }

    /* Fluid api */
    public FlagVal name(String name){
        this.name=name;
        return this;
    }
    public FlagVal group(String group){
        this.group=group;
        return this;
    }
    public FlagVal enableTimekeeping(){
        keepTime=true;
        return this;
    }

}
