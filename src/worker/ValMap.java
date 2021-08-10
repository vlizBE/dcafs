package worker;

import das.DataProviding;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.Tools;
import util.xml.XMLtools;

import java.util.HashMap;
import java.util.StringJoiner;

public class ValMap {

    String split="";
    String multi="";
    HashMap<String,Mapping> mapping = new HashMap<>();
    String id="";

    public static ValMap create( String id){
        return new ValMap(id);
    }
    public ValMap( String id ){
        this.id=id;
    }

    public void setDelimiter(String delimiter){
        split = Tools.fromEscapedStringToBytes(delimiter);
    }
    public void setMultiSplit(String delimiter){
        multi = Tools.fromEscapedStringToBytes(delimiter);
    }

    public String getID(){
        return id;
    }

    public void apply(String data, DataProviding dp){
        if( multi.isEmpty()) {
            processSingle(data,dp);
        }else {
            for (String piece : data.split(multi))
                processSingle(piece,dp);
        }
    }
    private void processSingle( String data, DataProviding dp ){
        String[] pair = data.split(split);
        Mapping mapped;

        if (pair.length == 2) {
            mapped = mapping.get(pair[0]);
        } else if (pair.length == 1) {
            mapped = mapping.get("");
        } else {
            Logger.error(id + " -> No proper delimited data received: " + data);
            return;
        }

        if (mapped != null) {
            if (!mapped.rtval.isEmpty()) {
                try {
                    dp.setDouble(mapped.rtval, NumberUtils.createDouble(pair[1]));
                } catch (NumberFormatException e) {
                    Logger.error(id + " -> No valid number in " + data);
                }
            }
            if (!mapped.rttext.isEmpty())
                dp.setText(mapped.rttext, mapped.convert(pair[1]));
        } else {
            //Logger.warn(id + " -> No mapping found for " + data);
        }
    }
    public String toString(){
        StringJoiner join = new StringJoiner("\r\n");

        join.add(id+" which splits on "+getReadableSplit());
        for( var pair : mapping.entrySet())
            join.add(" - maps "+pair.getKey()+" to "+pair.getValue());
        return join.toString();
    }
    private String getReadableSplit(){
        String readsplit=split;
        if( split.length()==1){
            char a = split.charAt(0);
            if( a < 33)
                readsplit = "\\x"+Integer.toHexString(a);
        }
        return readsplit;
    }
    private void addRTValMap(String key,String rtval){
        mapping.put(key, new Mapping(rtval, ""));
    }
    private void addRTTextMap(String key,String rttext){
        mapping.put(key, new Mapping("", rttext));
    }
    private void addText(String id, String key, String value){
        if( !mapping.containsKey(id) ){
            Logger.error("No such id "+id);
            return;
        }
        mapping.get(id).addText(key,value);
    }
    private void setRtText(String id, String rtt){
        var map = mapping.get(id);
        if( map != null && rtt!=null)
            map.rttext=rtt;
    }
    private void setRtVal(String id, String rtv){
        var map = mapping.get(id);
        if( map != null && rtv!=null)
            map.rtval=rtv;
    }
    public static ValMap readFromXML( Element vmEle ){
        var map = ValMap.create(vmEle.getAttribute("id"));
        map.setDelimiter(vmEle.getAttribute("split"));
        map.setMultiSplit(vmEle.getAttribute("delimiter"));
        Logger.info("Building ValMap "+map.id+" which splits on "+map.getReadableSplit());

        for( var pair : XMLtools.getChildElements(vmEle,"map")){
            String key = XMLtools.getStringAttribute(pair,"key","");
            String value = pair.getTextContent();

            if( value != null ) {
                if (XMLtools.hasChildByTag(pair, "rttext")) {
                    map.addRTTextMap(key, value);
                    map.setRtVal(key, XMLtools.getChildValueByTag(pair,"rtval",""));

                    for (var rtt : XMLtools.getChildElements(pair, "rttext")) {
                        String rttext = XMLtools.getStringAttribute(rtt, "ref", "");
                        if (!rttext.isEmpty()) {
                            map.setRtText(key, rttext);
                            for (var rt : XMLtools.getChildElements(rtt, "text")) {
                                String val = XMLtools.getStringAttribute(rt, "val", "");
                                map.addText(key, val, rt.getTextContent());
                            }
                        } else {
                            Logger.error(key + " -> No valid rttext reference");
                        }
                    }
                }else if(!value.isEmpty()){
                    map.addRTValMap(key, value);
                    Logger.info(map.id + " -> Adding single " + key + " > " + value);
                }else{
                    Logger.error(map.id+" -> Bad element");
                }
            }
        }
        return map;
    }
    private static class Mapping{
        HashMap<String,String> texts = new HashMap<>();
        String rtval="";
        String rttext="";

        public Mapping( String rtval, String rttext){
            this.rtval=rtval;
            this.rttext=rttext;
        }

        public void addText(String key, String value){
            texts.put(key,value);
        }
        public String convert( String txt ){
            var t =  texts.get(txt);
            if( t==null)
                return  txt;
            return t;
        }
    }
}
