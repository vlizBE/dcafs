package util.data;

import worker.Datagram;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;

public class FlagVal implements NumericVal{

    String group="";
    String name="";

    boolean state=false;
    boolean defState=false;

    /* Keep Time */
    long timestamp;
    boolean keepTime=false;

    BlockingQueue<Datagram> dQueue;

    public FlagVal(){}
    public FlagVal(boolean state){
        setState(state);
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
    /* **************************** C O N S T R U C T I N G ******************************************************* */
    public FlagVal name(String name){
        this.name=name;
        return this;
    }
    public FlagVal group(String group){
        this.group=group;
        return this;
    }
    public FlagVal setState( boolean val){
        /* Keep time of last value */
        if( keepTime )
            timestamp= Instant.now().toEpochMilli();
        state=val;
        return this;
    }
    public FlagVal enableTimekeeping(){
        keepTime=true;
        return this;
    }
    public FlagVal defState( boolean defState){
        this.defState = defState;
        state=defState;
        return this;
    }

    /* *************************************** U S I N G *********************************************************** */
    public String id(){
        return group.isEmpty()?name:(group+"_"+name);
    }
    public String group(){
        return group;
    }
    public String name(){
        return name;
    }
    @Override
    public void updateValue(double val) {
        state = Double.compare(val,0.0)!=0;
    }
    public BigDecimal toBigDecimal(){
        return state?BigDecimal.ONE:BigDecimal.ZERO;
    }
    public boolean equals(FlagVal fv){
        return state == fv.isUp();
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
    public double value(){
        return state?1:0;
    }
    public String toString(){
        return ""+state;
    }

}
