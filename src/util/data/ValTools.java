package util.data;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.math.MathUtils;
import util.tools.TimeTools;
import util.tools.Tools;

import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class ValTools {

    private final static Pattern words = Pattern.compile("[a-zA-Z]+[_:\\d]*[a-zA-Z\\d]+\\d*");

    /**
     * Checks the exp for any mentions of the numerical rtvals and if found adds these to the nums arraylist and replaces
     * the reference with 'i' followed by the index in nums+offset.
     * The reason for doing this is mainly parse once, use expression many times

     * fe. {r:temp}+30, nums still empty and offset 1: will add the RealVal temp to nums and alter exp to i1 + 30
     * @param exp The expression to check
     * @param nums The Arraylist to hold the numerical values
     * @param offset The index offset to apply
     * @return The altered expression
     */
    public static String buildNumericalMem(RealtimeValues rtvals, String exp, ArrayList<NumericVal> nums, int offset){
        if( nums==null)
            nums = new ArrayList<>();

        // Find all the real/flag/int pairs
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

    /**
     * Process an expression that contains both numbers and references and figure out the result
     *
     * @param expr The expression to process
     * @param rv The realtimevalues store
     * @return The result or NaN if failed
     */
    public static double processExpression( String expr, RealtimeValues rv  ){
        double result=Double.NaN;

        expr = ValTools.parseRTline(expr,"",rv);
        expr = expr.replace("true","1");
        expr = expr.replace("false","0");

        expr = ValTools.simpleParseRT(expr,"",rv); // Replace all references with actual numbers if possible

        if( expr.isEmpty()) // If any part of the conversion failed
            return result;

        var parts = MathUtils.extractParts(expr);
        if( parts.size()==1 ){
            result = NumberUtils.createDouble(expr);
        }else if (parts.size()==3){
            result = Objects.requireNonNull(MathUtils.decodeDoublesOp(parts.get(0), parts.get(2), parts.get(1), 0)).apply(new Double[]{});
        }else{
            try {
                result = MathUtils.simpleCalculation(expr, Double.NaN, false);
            }catch(IndexOutOfBoundsException e){
                Logger.error("Index out of bounds while processing "+expr);
                return Double.NaN;
            }
        }
        return result;
    }
    /**
     * Stricter version to parse a realtime line, must contain the references within { }
     * Options are:
     * - RealVal: {d:id} and {real:id}
     * - FlagVal: {f:id} or {b:id} and {flag:id}
     * This also checks for {utc}/{utclong},{utcshort} to insert current timestamp
     * @param line The original line to parse/alter
     * @param error Value to put if the reference isn't found
     * @return The (possibly) altered line
     */
    public static String parseRTline( String line, String error, RealtimeValues rv ){

        if( !line.contains("{"))
            return line;

        var pairs = Tools.parseKeyValue(line,true);
        for( var p : pairs ){
            if(p.length==2) {
                switch (p[0]) {
                    case "d", "r", "double", "real" -> {
                        var d = rv.getReal(p[1], Double.NaN);
                        if (!Double.isNaN(d) || !error.isEmpty())
                            line = line.replace("{" + p[0] + ":" + p[1] + "}", Double.isNaN(d) ? error : "" + d);
                    }
                    case "i", "int", "integer" -> {
                        var i = rv.getInteger(p[1], Integer.MAX_VALUE);
                        if (i != Integer.MAX_VALUE)
                            line = line.replace("{" + p[0] + ":" + p[1] + "}", "" + i);
                    }
                    case "t", "text" -> {
                        String t = rv.getText(p[1], error);
                        if (!t.isEmpty())
                            line = line.replace("{" + p[0] + ":" + p[1] + "}", t);
                    }
                    case "f", "b", "flag" -> {
                        var d = rv.getFlagVal(p[1]);
                        var r = d.map(FlagVal::toString).orElse(error);
                        if (!r.isEmpty())
                            line = line.replace("{" + p[0] + ":" + p[1] + "}", r);
                    }
                }
            }else{
                line = switch(p[0]){
                    case "utc" -> line.replace("{utc}", TimeTools.formatLongUTCNow());
                    case "utclong" -> line.replace("{utclong}", TimeTools.formatLongUTCNow());
                    case "utcshort"-> line.replace("{utcshort}", TimeTools.formatShortUTCNow());
                };
            }
        }
        if( line.toLowerCase().matches(".*[{][drfi]:.*") && !pairs.isEmpty()){
            Logger.warn("Found a {*:*}, might mean parsing a section of "+line+" failed");
        }
        return line;
    }
    /**
     * Simple version of the parse realtime line, just checks all the words to see if any matches the hashmaps.
     * If anything goes wrong, the 'error' will be returned. If this is set to ignore if something is not found it
     * will be replaced according to the type: real-> NaN, int -> Integer.MAX
     * @param line The line to parse
     * @param error The line to return on an error or 'ignore' if errors should be ignored
     * @return The (possibly) altered line
     */
    public static String simpleParseRT( String line,String error, RealtimeValues rv ){

        var found = words.matcher(line).results().map(MatchResult::group).toList();
        for( var word : found ){
            String replacement;
            if( word.contains(":")){ // Check if the word contains a : with means it's {d:id} etc
                var id = word.split(":")[1];

                replacement = switch (word.charAt(0) ){
                    case 'd','r' -> {
                        if( !rv.hasReal(id) ){
                            Logger.error("No such real "+id+", extracted from "+line); // notify
                            if( !error.equalsIgnoreCase("ignore")) // if errors should be ignored
                                yield error;
                        }
                        yield ""+rv.getReal(id,Double.NaN);
                    }
                    case 'i' -> {
                        if( !rv.hasInteger(id) ) { // ID found
                            Logger.error("No such integer "+id+", extracted from "+line); // notify
                            if( !error.equalsIgnoreCase("ignore")) // if errors should be ignored
                                yield error;
                        }
                        yield "" + rv.getInteger(id,Integer.MAX_VALUE);
                    }
                    case 'f'-> {
                        if (!rv.hasFlag(id)) {
                            Logger.error("No such flag " + id + ", extracted from " + line);
                            if (!error.equalsIgnoreCase("ignore"))
                                yield error;
                        }
                        yield rv.getFlagState(id) ? "1" : "0";
                    }
                    case 't', 'T' -> {
                        if (!rv.hasText(id)){
                          if(word.charAt(0) == 'T') {
                              rv.setText(id, "");
                          }else{
                              Logger.error("No such text " + id + ", extracted from " + line);
                              if (!error.equalsIgnoreCase("ignore"))
                                  yield error;
                          }
                          yield "";
                        }else{
                            yield rv.getText(id,"");
                        }
                    }
                    default -> {
                        Logger.error("No such type: "+word.charAt(0));
                        yield error;
                    }
                };
            }else { // If it doesn't contain : it could be anything...
                if (rv.hasReal(word)) { //first check for real
                    replacement = "" + rv.getReal(word,Double.NaN);
                } else { // if not
                    if( rv.hasInteger(word)){
                        replacement = ""  + rv.getInteger(word,-999);
                    }else {
                        if (rv.hasText(word)) { //next, try text
                            replacement = rv.getText(word,"");
                        } else if (rv.hasFlag(word)) { // if it isn't a text, check if it's a flag
                            replacement = rv.getFlagState(word) ? "1" : "0";
                        } else{
                            Logger.error("Couldn't process " + word + " found in " + line); // log it and abort
                            return error;
                        }
                    }
                }
            }
            assert replacement != null;
            if( replacement.equalsIgnoreCase(error))
                return error;
            if( !replacement.isEmpty() )
                line = line.replace(word,replacement);
        }
        return line;
    }
}
