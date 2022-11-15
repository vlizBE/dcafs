package util.data;

import org.tinylog.Logger;
import util.tools.Tools;

import java.util.ArrayList;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class ValTools {

    private final static Pattern words = Pattern.compile("[a-zA-Z]+[_:\\d]*[a-zA-Z\\d]+\\d*");

    /**
     * Checks the exp for any mentions of the numerical rtvals and if found adds these to the nums arraylist and replaces
     * the reference with 'i' followed by the index in nums+offset. {D:id} and {F:id} will add the rtvals if they don't
     * exist yet.

     * fe. {d:temp}+30, nums still empty and offset 1: will add the RealVal temp to nums and alter exp to i1 + 30
     * @param exp The expression to check
     * @param nums The Arraylist to hold the numerical values
     * @param offset The index offset to apply
     * @return The altered expression
     */
    public static String buildNumericalMem(RealtimeValues rtvals, String exp, ArrayList<NumericVal> nums, int offset){
        if( nums==null)
            nums = new ArrayList<>();

        // Find all the real/flag pairs
        var pairs = Tools.parseKeyValue(exp,true); // Add those of the format {d:id}
        //pairs.addAll( Tools.parseKeyValueNoBrackets(exp) ); // Add those of the format d:id

        for( var p : pairs ) {
            boolean ok=false;
            if (p.length == 2) {
                for( int pos=0;pos<nums.size();pos++ ){ // go through the known realVals
                    var d = nums.get(pos);
                    if( d.id().equalsIgnoreCase(p[1])) { // If a match is found
                        exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + (offset + pos));
                        exp = exp.replace(p[0] + ":" + p[1], "i" + (offset + pos));
                        ok=true;
                        break;
                    }
                }
                if( ok )
                    continue;
                int index;
                switch (p[0]) {
                    case "d", "double", "r", "real" -> {
                        var d = rtvals.getRealVal(p[1]);
                        if (d.isPresent()) {
                            index = nums.indexOf(d.get());
                            if (index == -1) {
                                nums.add(d.get());
                                index = nums.size() - 1;
                            }
                            index += offset;
                            exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
                        } else {
                            Logger.error("Couldn't find a real with id " + p[1]);
                            return "";
                        }
                    }
                    case "int", "i" -> {
                        var ii = rtvals.getIntegerVal(p[1]);
                        if (ii.isPresent()) {
                            index = nums.indexOf(ii.get());
                            if (index == -1) {
                                nums.add(ii.get());
                                index = nums.size() - 1;
                            }
                            index += offset;
                            exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
                        } else {
                            Logger.error("Couldn't find a integer with id " + p[1]);
                            return "";
                        }
                    }
                    case "f", "flag", "b" -> {
                        var f = rtvals.getFlagVal(p[1]);
                        if (f.isPresent()) {
                            index = nums.indexOf(f.get());
                            if (index == -1) {
                                nums.add(f.get());
                                index = nums.size() - 1;
                            }
                            index += offset;
                            exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
                        } else {
                            Logger.error("Couldn't find a FlagVal with id " + p[1]);
                            return "";
                        }
                    }
                    case "is" -> { // issues
                        var i = rtvals.getIssuePool().getIssueAsNumerical(p[1]);
                        if (i.isPresent()) {
                            index = nums.indexOf(i.get());
                            if (index == -1) {
                                nums.add(i.get());
                                index = nums.size() - 1;
                            }
                            index += offset;
                            exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + index);
                        } else {
                            Logger.error("Couldn't find an Issue with id " + p[1]);
                            return "";
                        }
                    }
                    default -> {
                        Logger.error("Operation containing unknown pair: " + p[0] + ":" + p[1]);
                        return "";
                    }
                }
            }else{
                Logger.error( "Pair containing odd amount of elements: "+String.join(":",p));
            }
        }
        // Figure out the rest?
        var found = words.matcher(exp).results().map(MatchResult::group).toList();
        for( String fl : found){
            if( fl.matches("^i\\d+") )
                continue;
            int index;
            if( fl.startsWith("flag:")){
                var f = rtvals.getFlagVal(fl.substring(5));
                if( f.isPresent() ){
                    index = nums.indexOf(f.get());
                    if(index==-1){
                        nums.add( f.get() );
                        index = nums.size()-1;
                    }
                    index += offset;
                    exp = exp.replace(fl, "i" + index);
                }else{
                    Logger.error("Couldn't find a FlagVal with id "+fl);
                    return "";
                }
            }else{
                var d = rtvals.getRealVal(fl);
                if( d.isPresent() ){
                    index = nums.indexOf(d.get());
                    if(index==-1){
                        nums.add( d.get() );
                        index = nums.size()-1;
                    }
                    index += offset;
                    exp = exp.replace(fl, "i" + index);
                }else{
                    var f = rtvals.getFlagVal(fl);
                    if( f.isPresent() ){
                        index = nums.indexOf(f.get());
                        if(index==-1){
                            nums.add( f.get() );
                            index = nums.size()-1;
                        }
                        index += offset;
                        exp = exp.replace(fl, "i" + index);
                    }else{
                        Logger.error("Couldn't find a realval with id "+fl);
                        return "";
                    }
                    return exp;
                }
            }
        }
        nums.trimToSize();
        return exp;
    }
}
