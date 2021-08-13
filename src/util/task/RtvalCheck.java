package util.task;

import util.data.DataProviding;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.math.MathUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RtvalCheck {

    public enum CHECKTYPE {NONE,SINGLE,AND,OR}

    Function<Double[],Boolean> compare;

    ArrayList<String> is = new ArrayList<>();
    ArrayList<Function<Double[],Boolean>> comparisons = new ArrayList<>();
    CHECKTYPE type = CHECKTYPE.NONE;
    String ori;

    public RtvalCheck(String equ){
        ori=equ;

        if( equ.isEmpty())
            return;

        equ=equ.replace(" not below ",">=");
        equ=equ.replace(" not above ","<=");
        equ=equ.replace(" not ","!=");
        equ=equ.replace(" below ","<");   // retain support for below
        equ=equ.replace(" above ",">");   // retain support for above
        equ=equ.replace(" equals ","=="); // retain support for equals

        // Split on and/or etc?
        if( equ.contains(" and ") ){
            for( String and : equ.split(" and "))
                comparisons.add(getCompareFunction(and));
            if( !comparisons.contains(null))
                type = CHECKTYPE.AND;
        }else if( equ.contains(" or ") ){
            for( String and : equ.split(" or "))
                comparisons.add(getCompareFunction(and));
            if( !comparisons.contains(null))
                type = CHECKTYPE.OR;
        }else{
            comparisons.add(getCompareFunction(equ));
            if( !comparisons.contains(null))
                type = CHECKTYPE.SINGLE;
        }
    }
    public String getOriginalFunction(){
        return ori;
    }
    public boolean isEmpty(){
        return type==CHECKTYPE.NONE;
    }
    private Function<Double[],Boolean> getCompareFunction( String equ){
        var results = Pattern.compile("[><=!][=]?");
        var comp = results.matcher(equ)
                .results()
                .map(MatchResult::group)
                .collect(Collectors.joining());

        if( comp.isEmpty() ) {
            if (equ.contains("flag:") || equ.contains("issue:") ) { //These can only be true or false
                if( equ.startsWith("!") ) {
                    equ+= "==1";
                }else {
                    equ+="==0";
                    equ.substring(1); // remove the ! at the start
                }
                comp="==";
            } else {
                Logger.error("Req doesn't contain a comparison: "+ori);
                return null;
            }
        }

        String[] split = equ.split(comp);
        try {
            var f1 = getFunction(split[0]);
            var f2 = getFunction(split[1]);
            return MathUtils.getCompareFunction(comp,f1,f2);
        }catch(IndexOutOfBoundsException e ){
            Logger.error("Out of bounds whan processing:" +equ);
        }
        return null;
    }
    private Function<Double[],Double> getFunction( String equ ) throws IndexOutOfBoundsException{
        List<String> parts;
        // First check if it's the special diff function (difference between two values)
        if( equ.contains("diff") ){
            parts = new ArrayList<>();
            String[] ops = equ.split("diff");
            parts.add(ops[0].trim());
            parts.add("diff");
            parts.add(ops[1].trim());
        }else {
            // If it's just a regular mathematical thing
            parts = MathUtils.extractParts(equ);
        }

        // Left side, first check if it's a valid number
        if( !NumberUtils.isCreatable(parts.get(0)) ){
            //it's not a number but a reference (rtval,flag,issue etc)'
            is.add(parts.get(0)); // So store it
            parts.set(0,"i" + (is.size()-1)); // and replace the parts position with the index of it in is
        }

        if( parts.size()>1){ // if there are more than one part (fe. i0+5 instead of just i0)
            if( !NumberUtils.isCreatable(parts.get(2)) ) { // Check if it's not a number
                //it's not a number but a reference (rtval,flag,issue etc)'
                is.add(parts.get(2).toLowerCase()); // So store it
                parts.set(2, "i" + (is.size()-1));  // and replace the parts position with the index of it in is
            }
        }else{ // we always want two sides, so add one that doesn't do anything
            parts.add("+");
            parts.add("0");
        }
        return MathUtils.decodeDoublesOp(parts.get(0),parts.get(2),parts.get(1),0);
    }
    public boolean test(DataProviding dp, ArrayList<String> activeIssues){
        if( type==CHECKTYPE.NONE ) {
            Logger.error("Trying to run an invalid RtvalCheck:"+ ori);
            return false;
        }
        Double[] vals = new Double[is.size()];
        for(int a=0;a< vals.length;a++){
            if( is.get(a).startsWith("flag:")){
                vals[a] = dp.isFlagUp(is.get(a).substring(5))?1.0:0;
            }else if( is.get(a).startsWith("issue:")){
                vals[a] = activeIssues.contains(is.get(a).substring(5))?1.0:0;
            }else{
                vals[a] = dp.getDouble(is.get(a),-999);
            }
        }
        for( var comp : comparisons ){
            if( !comp.apply(vals)){
                if( type == CHECKTYPE.AND || type==CHECKTYPE.SINGLE)
                    return false;
            }else{
                if( type==CHECKTYPE.OR )
                    return true;
            }
        }
        if( type == CHECKTYPE.AND || type==CHECKTYPE.SINGLE)
            return true;
        if( type==CHECKTYPE.OR )
            return false;
        return false;
    }
    public boolean test( DataProviding dp ){
        return test(dp, new ArrayList<String>());
    }
    public String toString(){
        return ori;
    }
    public String toString(DataProviding dp, ArrayList<String> activeIssues ){
        String rep = ori;
        for( String i : is){
            if( i.startsWith("flag:")){
                rep=rep.replace(i,dp.isFlagUp(i.substring(5))?"true":"false");
            }else if( i.startsWith("issue:")){
                rep=rep.replace(i,activeIssues.contains(i.substring(5))?"true":"false");
            }else{
                rep=rep.replace(i,""+dp.getDouble(i,-999));
            }
        }
        return ori +" -> "+rep + "=> "+test(dp,activeIssues);
    }
}
