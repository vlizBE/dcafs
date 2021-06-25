package com.collector;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.xml.XMLtools;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MathCollector extends AbstractCollector{

    ArrayList<BigDecimal> bds = new ArrayList<>();
    int count;
    boolean moving;
    String delimiter;
    String rtval;
    int index;
    Function<List<BigDecimal>,BigDecimal> op;

    public MathCollector( String id, String rtval, String delimiter,int index ){
        super(id);
        this.delimiter=delimiter;
        this.index=index;
        this.rtval=rtval;
    }
    public static MathCollector createAvg( String id, String rtval, String delimiter,  int index, int count, int decimals){
        Logger.info("Creating Avg Math Collector for "+rtval);
        var mc = new MathCollector(id,rtval,delimiter,index);
        mc.moving=false;
        mc.count=count;

        mc.op = list -> {
            return list.stream().reduce(BigDecimal::add)
                    .map( sum -> sum.divide(BigDecimal.valueOf(list.size()), new MathContext(decimals,RoundingMode.HALF_UP))).orElse(null); // Sum of all elements
        };
        return mc;
    }
    public static MathCollector createMovingAvg( String id, String rtval, String delimiter, int index, int count, int decimals){
        var mc = MathCollector.createAvg(id,rtval,delimiter,index,count,decimals);
        mc.moving=true;
        return mc;
    }
    public static MathCollector createStDev( String id, String rtval, String delimiter, int index, int count, int decimals){
        var mc = new MathCollector(id,rtval,delimiter,index);
        mc.moving=false;
        mc.count=count;

        mc.op = list -> {
            var mean = list.stream().reduce(BigDecimal::add)
                    .map( sum -> sum.divide(BigDecimal.valueOf(list.size()), new MathContext(8,RoundingMode.HALF_UP))).orElse(null); // Sum of all elements
            if( mean != null){
                var sum2 = list.stream().reduce( BigDecimal.ZERO, (sum,bd) -> sum.add(mean.subtract(bd).pow(2)) );
                return sum2.divide(BigDecimal.valueOf(list.size())).sqrt(new MathContext(decimals+1,RoundingMode.HALF_UP));
            }else{
                return BigDecimal.valueOf(-999);
            }
        };
        return mc;
    }
    public static MathCollector createMovingStDev( String id, String rtval, String delimiter, int index, int count, int decimals){
        var mc = MathCollector.createStDev(id,rtval,delimiter,index,count,decimals);
        mc.moving=true;
        return mc;
    }
    public static List<MathCollector> createFromXml( Stream<Element> maths ){
        var mcs = new ArrayList<MathCollector>();

        for( Element mc : maths.collect(Collectors.toList()) ){
            String id = XMLtools.getStringAttribute(mc,"id","");
            int count = XMLtools.getIntAttribute(mc,"count",-1);
            int index = XMLtools.getIntAttribute(mc,"index",0);
            String delimiter = XMLtools.getStringAttribute(mc,"delimiter",";");
            int decimals = XMLtools.getIntAttribute(mc,"decimals",0);
            String src = XMLtools.getStringAttribute(mc,"src","");

            String target = mc.getTextContent();
            MathCollector collector=null;
            switch( mc.getTagName() ){
                case "stdev": collector=MathCollector.createStDev(id,target,delimiter,index,count,decimals); break;
                case "movingstdev": collector=MathCollector.createMovingStDev(id,target,delimiter,index,count,decimals); break;
                case "avg": collector=MathCollector.createAvg(id,target,delimiter,index,count,decimals); break;
                case "movingavg": collector=MathCollector.createMovingAvg(id,target,delimiter,index,count,decimals); break;
                default: break;
            }
            if( collector != null ){
                collector.addSource(src);
                mcs.add(collector);
            }
        }
        return mcs;
    }
    @Override
    protected boolean addData(String data) {
        Logger.debug("Data received: "+data);
        var split = data.split(delimiter);
        if( split.length>index){
            try {
                BigDecimal bd = NumberUtils.createBigDecimal(split[index].trim());
                bds.add(bd);
            }catch(NumberFormatException e){
                Logger.error(e);
            }
            if( bds.size()>=count ){
                var result = op.apply(bds);
                Logger.debug(id+" -> result: "+result.toPlainString());
                if( !moving ) {
                    bds.clear();
                    valid=false;
                }else{
                    bds.remove(0);
                }
                listeners.forEach( rw -> rw.collectorFinished("math:"+id,rtval, result.doubleValue()) );
            }
        }
        return true;
    }

    @Override
    protected void timedOut() {

    }
}
