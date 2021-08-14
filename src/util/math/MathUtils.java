package util.math;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.Tools;

import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MathUtils {
    final static int DIV_SCALE = 8;
    static final String[] ORDERED_OPS={"^","^","*","/","%","%","+","-"};
    static final String[] COMPARES={"<","<=","==","!=",">=",">"};
    static final String OPS_REGEX="\\+|/|\\*|-|\\^|%";
    static final Pattern es = Pattern.compile("\\de[+-]?\\d");
    /**
     * Splits a simple expression of the type i1+125 etc into distinct parts i1,+,125
     * @param expression The expression to split
     * @param indexOffset The offset to apply to the index
     * @param debug Give extra debug information
     * @return The result of the splitting, so i1+125 => {"i1","+","125"}
     */
    public static List<String[]> splitExpression(String expression, int indexOffset, boolean debug ){
        var result = new ArrayList<String[]>();

        expression=expression.replace("+-","-"); // adding a negative number is the same as subtracting it

        var parts = extractParts(expression);

        if( debug ){
            Logger.info("-> Splitting: "+expression);
        }
        indexOffset++;
        try {
            if (parts.size() == 3) {
                if (debug) {
                    Logger.info("  Sub: " + "o" + indexOffset + "=" + expression);
                }
                result.add(new String[]{parts.get(0), parts.get(2), parts.get(1)});
            } else {
                int oIndex = indexOffset;
                for (int a = 0; a < ORDERED_OPS.length; a += 2) {

                    int opIndex = getIndexOfOperand(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);

                    while (opIndex != -1) {
                        if( parts.size()<=opIndex+1) {
                            Logger.error("Not enough data in parts...");
                            return result;
                        }
                        String res = parts.get(opIndex - 1) + parts.get(opIndex) + parts.get(opIndex + 1);
                        result.add(new String[]{parts.get(opIndex - 1), parts.get(opIndex + 1), parts.get(opIndex)});
                        parts.remove(opIndex);  // remove the operand
                        parts.remove(opIndex);  // remove the top part
                        parts.set(opIndex - 1, "o" + oIndex); // replace the bottom one

                        if (debug) {
                            Logger.info("  Sub: " + "o" + oIndex + "=" + res);
                        }
                        expression = expression.replace(res, "o" + oIndex++);
                        opIndex = getIndexOfOperand(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);
                    }
                }
            }
        }catch( IndexOutOfBoundsException e){
            Logger.error("Index issue while processing "+expression);
            Logger.error(e);
        }
        return result;
    }
    private static int getIndexOfOperand( List<String> data, String op1, String op2){
        int op1Index = data.indexOf(op1);
        int op2Index = data.indexOf(op2);

        if( op1Index==-1) { // first op can't be found, so it's e
            return op2Index;
        }
        if( op2Index==-1){
            return op1Index;
        }else{
            return Math.min( op1Index,op2Index);
        }
    }
    public static List<String> extractParts( String formula ){

        var ee = es.matcher(formula)
                .results()
                .map(MatchResult::group)
                .distinct()
                .collect(Collectors.toList());

        for( String el : ee ){
            formula = formula.replace(el,el.toUpperCase());
        }

        String alt = formula.replace("E-","e");
        alt = alt.replace("E+","E");

        String[] spl = alt.split(OPS_REGEX);

        String ops = alt.replaceAll("[a-zA-Z0-9_:]", "");
        ops=ops.replace(".","");
        // The above replace all doesn't handle it properly if the formula starts with a - (fe. -5*i1)
        // So if this is the case, remove it from the ops line
        if( ops.startsWith("-")&&formula.startsWith("-"))
            ops=ops.substring(1);

        var full = new ArrayList<String>();
        int b=0;
        for (int a = 0; a < spl.length; a++) {
            if (spl[a].isEmpty()) {
                spl[a + 1] = "-" + spl[a + 1];
            } else {
                var m = es.matcher(spl[a]);
                if( m.find() ){
                    full.add(spl[a].replace("e","E-"));
                }else{
                    full.add(spl[a]);
                }

                // add the op
                if( b<ops.length())
                    full.add(""+ops.charAt(b));
                b++;
            }
        }
        return full;
    }
    public static Function<double[],Boolean> getCompareFunction( String comp ){
        Function<double[],Boolean> proc=null;
        switch( comp ){
            case "<": proc = x -> x[0]<x[1]; break;
            case "<=": proc = x -> x[0]<=x[1]; break;
            case ">": proc = x -> x[0]>x[1]; break;
            case ">=": proc = x -> x[0]>=x[1]; break;
            case "==": proc = x -> x[0]==x[1]; break;
            case "!=": proc = x -> x[0]!=x[1]; break;
        }
        return proc;
    }
    public static Function<Double[],Boolean> getCompareFunction( String comp, Function<Double[],Double> f1, Function<Double[],Double> f2 ){
        Function<Double[],Boolean> proc=null;
        switch( comp ){
            case "<": proc = x -> f1.apply(x)<f2.apply(x); break;
            case "<=": proc = x -> f1.apply(x)<=f2.apply(x); break;
            case ">": proc = x -> f1.apply(x)>f2.apply(x); break;
            case ">=": proc = x -> f1.apply(x)>=f2.apply(x); break;
            case "==": proc = x -> f1.apply(x)==f2.apply(x); break;
            case "!=": proc = x -> f1.apply(x)!=f2.apply(x); break;
        }
        return proc;
    }
    public static String[] splitCompare( String comparison ){
        var results = Pattern.compile("[><=!][=]?");
        var full = new String[3];
        full[1] = results.matcher(comparison)
                .results()
                .map(MatchResult::group)
                .collect(Collectors.joining());
        var split = comparison.split(full[1]);
        full[0]=split[0];
        full[2]=split[1];
        return full;
    }


    /**
     * Parse a comparator operation with a single variable to a function, allowed formats:
     * - Using <,>,=,!= so : <50,>30,x<25,y==65,z<=125.2 etc
     * - Combining two like 1 < x < 10
     * - using above or below: above 1, below 10
     * - using not or equals: not 5, equals 10
     * - combining with not: not below 5 (>=5) or not above 10 (<=10)
     * - Maximum of two can be combined: 'above 1, below 10' = '1<x<10' (or ; as separator)
     * - between 20 and 50 will be parsed to 20<x<50
     * - from 1 to 10 will be parsed to 1<=x<10
     * - 1 through 10 will be parsed to 1<=x<=10
     * - or a range with '-' or '->' so 1-10 or -5->15
     * @param op An operation in the understood format
     * @return The combined functions that takes x and returns the result
     */
    public static Function<Double,Boolean> parseSingleCompareFunction( String op ){
        var comparePattern = Pattern.compile("[><=!][=]?");

        op=op.replace("->","-");

        // between 40 and 50
        if( op.startsWith("between") ){
            op=op.replace("between ",">");
            op=op.replace(" and ", ";<");
        }
        if( op.startsWith("not between") ){
            op=op.replace("not between ","<=");
            op=op.replace(" and ", ";>=");
        }
        if( op.startsWith("from ") ){
            op=op.replace("from ",">");
            op=op.replace(" to ", ";<");
            op=op.replace(" till ", ";<");
        }
        if( op.contains(" through ")){
            op=op.replace(" through ", "<=var<=");
        }
        // 15 < x <= 25   or x <= 25
        op = op.replace("not below ",">=");   // retain support for below
        op = op.replace("not above ","<=");   // retain support for above
        op = op.replace("below ","<");   // retain support for below
        op = op.replace("above ",">");   // retain support for above
        op = op.replace("equals ","=="); // retain support for equals
        op = op.replace("not ","!="); // retain support for not equals

        op = op.replace(" ",""); // remove all spaces

        var cc = comparePattern.matcher(op)
                .results()
                .map(MatchResult::group)
                .collect(Collectors.toList());
        if( cc.isEmpty() ){ // fe. 1-10
            op=op.replace("--","<=var<=-");
            op=op.replace("-","<=var<=");
            if( op.startsWith("<=var<="))
                op = "-"+op.substring(7);
        }else if( cc.size()==1){
            var c1 = op.split(cc.get(0));
            double fi1 = NumberUtils.toDouble(c1[1]);
            return getSingleCompareFunction(fi1,cc.get(0));
        }
        double fi1;
        Function<Double,Boolean> fu1;
        if( op.startsWith(cc.get(0))){
            fi1 = NumberUtils.toDouble(op.substring( cc.get(0).length(),op.lastIndexOf(cc.get(1))-1));
            fu1 = getSingleCompareFunction(fi1,cc.get(1));
        }else{
            fi1 = NumberUtils.toDouble(op.substring( 0,op.indexOf(cc.get(0))));
            fu1 = getSingleCompareFunction(fi1,invertCompare(cc.get(1)));
        }

        double fi2 = NumberUtils.toDouble(op.substring( op.lastIndexOf(cc.get(1))+cc.get(1).length()));
        var fu2 = getSingleCompareFunction(fi2,cc.get(1));

        return x -> fu1.apply(x) && fu2.apply(x);
    }

    /**
     * Convert the given fixed value and comparison to a function that requires another double and return if correct
     * @param fixed The fixed part of the comparison
     * @param comp The type of comparison (options: <,>,<=,>=,!=,==)
     * @return The generated function
     */
    public static Function<Double,Boolean> getSingleCompareFunction( double fixed,String comp ){
        Function<Double,Boolean> proc=null;
        switch( comp ){
            case "<": proc = x -> x<fixed; break;
            case "<=": proc = x -> x<=fixed; break;
            case ">": proc = x -> x>fixed; break;
            case ">=": proc = x -> x>=fixed; break;
            case "==": proc = x -> x==fixed; break;
            case "!=": proc = x -> x!=fixed; break;
        }
        return proc;
    }

    /**
     * Invert the compare symbol, so < -> > and so forth
     * @param comp The original symbol
     * @return The inverted version
     */
    private static String invertCompare(String comp){
        switch( comp ){
            case "<": return ">";
            case "<=": return ">=";
            case ">": return "<";
            case ">=": return "<=";
            default: return comp;
        }
    }
    /**
     * Converts a simple operation (only two operands) on elements in an array to a function
     * @param first The first element of the operation
     * @param second The second element of the operation
     * @param op The operator to apply
     * @param offset The offset for the index in the array
     * @return The result of the calculation
     */
    public static Function<BigDecimal[],BigDecimal> decodeBigDecimalsOp(String first, String second, String op, int offset ){

        final BigDecimal bd1;
        final int i1;
        final BigDecimal bd2 ;
        final int i2;

        try{
            if(NumberUtils.isCreatable(first) ) {
                bd1 = NumberUtils.createBigDecimal(first);
                i1=-1;
            }else{
                bd1=null;
                int index = NumberUtils.createInteger( first.substring(1));
                i1 = first.startsWith("o")?index:index+offset;
            }
            if(NumberUtils.isCreatable(second) ) {
                bd2 = NumberUtils.createBigDecimal(second);
                i2=-1;
            }else{
                bd2=null;
                int index = NumberUtils.createInteger( second.substring(1));
                i2 = second.startsWith("o")?index:index+offset;
            }
        }catch( NumberFormatException e){
            Logger.error("Something went wrong decoding: "+first+" or "+second);
            return null;
        }

        Function<BigDecimal[],BigDecimal> proc=null;
        switch( op ){
            case "+":
                try {
                    if (bd1 != null && bd2 != null) { // meaning both numbers
                        proc = x -> bd1.add(bd2);
                    } else if (bd1 == null && bd2 != null) { // meaning first is an index and second a number
                        proc = x -> x[i1].add(bd2);
                    } else if (bd1 != null) { // meaning first is a number and second an index
                        proc = x -> bd1.add(x[i2]);
                    } else { // meaning both indexes
                        proc = x -> x[i1].add(x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "-":
                try{
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.subtract(bd2);
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
                        proc = x -> x[i1].subtract(bd2);
                    }else if(bd1 != null){ // meaning first is a number and second an index
                        proc = x -> bd1.subtract(x[i2]);
                    }else{ // meaning both indexes
                        proc = x -> x[i1].subtract(x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "*":
                try{
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.multiply(bd2);
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
                        proc = x -> x[i1].multiply(bd2);
                    }else if(bd1 != null){ // meaning first is a number and second an index
                        proc = x -> bd1.multiply(x[i2]);
                    }else{ // meaning both indexes
                        proc = x -> x[i1].multiply(x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;

            case "/": // i0/25
                try {
                    if (bd1 != null && bd2 != null) { // meaning both numbers
                        proc = x -> bd1.divide(bd2, DIV_SCALE, RoundingMode.HALF_UP);
                    } else if (bd1 == null && bd2 != null) { // meaning first is an index and second a number
                        proc = x -> x[i1].divide(bd2, DIV_SCALE, RoundingMode.HALF_UP);
                    } else if (bd1 != null) { //  meaning first is a number and second an index
                        proc = x -> bd1.divide(x[i2], DIV_SCALE, RoundingMode.HALF_UP);
                    } else { // meaning both indexes
                        proc = x -> x[i1].divide(x[i2], DIV_SCALE, RoundingMode.HALF_UP);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;

            case "%": // i0%25
                try{
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.remainder(bd2);
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
                        proc = x -> x[i1].remainder(bd2);
                    }else if(bd1 != null){ //  meaning first is a number and second an index
                        proc = x -> bd1.remainder(x[i2]);
                    }else{ // meaning both indexes
                        proc = x -> x[i1].remainder(x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "^": // i0/25
                try{
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.pow(bd2.intValue());
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
                        if( bd2.compareTo(BigDecimal.valueOf(0.5)) == 0){ // root
                            proc = x -> x[i1].sqrt(MathContext.DECIMAL64);
                        }else{
                            proc = x -> x[i1].pow(bd2.intValue());
                        }

                    }else if(bd1 != null){ //  meaning first is a number and second an index
                        proc = x -> bd1.pow(x[i2].intValue());
                    }else{ // meaning both indexes
                        proc = x -> x[i1].pow(x[i2].intValue());
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "scale": // i0/25
                try{
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.setScale(bd2.intValue(),RoundingMode.HALF_UP);
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
                        proc = x -> x[i1].setScale(bd2.intValue(),RoundingMode.HALF_UP);
                    }else if(bd1 != null){ //  meaning first is a number and second an index
                        proc = x -> bd1.setScale(x[i2].intValue(),RoundingMode.HALF_UP);
                    }else{ // meaning both indexes
                        proc = x -> x[i1].setScale(x[i2].intValue(),RoundingMode.HALF_UP);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "ln":
                try{
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        Logger.error("Todo - ln bd,bd");
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
                        Logger.error("Todo - ln ix,bd");
                    }else if(bd1 != null){ //  meaning first is a number and second an index
                        proc = x -> BigDecimal.valueOf(Math.log(x[i2].doubleValue()));
                    }else{ // meaning both indexes
                        proc = x -> BigDecimal.valueOf(Math.log(x[i2].doubleValue()));
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            default:Logger.error("Unknown operand: "+op); break;
        }
        return proc;
    }
    public static double simpleCalculation( String formula,double error, boolean debug ){
        ArrayList<Function<Double[],Double>> steps = new ArrayList<>();

        // First check if the amount of brackets is correct
        int opens = StringUtils.countMatches(formula,"(");
        int closes = StringUtils.countMatches(formula,")");


        if( opens != closes ){
            Logger.error("Brackets don't match, (="+opens+" and )="+closes);
            return error;
        }

        formula = checkBrackets(formula); // Then make sure it has surrounding brackets
        formula=formula.replace(" ",""); // But doesn't contain any spaces

        // Next go through the brackets from left to right (inner)
        var subFormulas = new ArrayList<String[]>(); // List to contain all the subformulas

        while( formula.contains("(") ){ // Look for an opening bracket
            int close = formula.indexOf(")"); // Find the first closing bracket
            int look = close-1; // start looking from one position left of the closing bracket
            int open = -1; // reset the open position to the not found value

            while( look>=0 ){ // while we didn't traverse the full string
                if( formula.charAt(look)=='(' ){ // is the current char an opening bracket?
                    open = look; // if so, store this position
                    break;// and quite the loop
                }
                look --;//if not, decrement the pointer
            }
            if( open !=-1 ){ // if the opening bracket was found
                String part = formula.substring(open+1,close); // get the part between the brackets
                subFormulas.addAll( MathUtils.splitExpression( part, subFormulas.size(),debug) );    // split that part in the subformulas
                String piece = formula.substring(open,close+1); // includes the brackets
                // replace the sub part in the original formula with a reference to the last subformula
                formula=formula.replace(piece,"o"+(subFormulas.size()));
                if( debug )
                    Logger.info("=>Formula: "+formula);
            }else{
                Logger.error("Didn't find opening bracket");
            }
        }

        for( String[] sub : subFormulas ){ // now convert the subformulas into lambda's
            var x = MathUtils.decodeDoublesOp(sub[0],sub[1],sub[2],0);
            if( x==null ){
                Logger.error("Failed to convert "+formula);
                continue;
            }
            steps.add( x ); // and add it to the steps list
        }
        Double[] result = new Double[subFormulas.size()+1];
        int i=1;
        try {
            for (var step : steps) {
                result[i] = step.apply(result);
                i++;
            }
        }catch( NullPointerException e ){
            Logger.error( "Nullpointer when processing "+formula+" on step "+i);
            return error;
        }
        return result[i-1];
    }
    public static String checkBrackets( String formula ){

        // No total enclosing brackets
        int cnt=0;
        for( int a=0;a<formula.length()-1;a++){
            if( formula.charAt(a)=='(') {
                cnt++;
            }else if( formula.charAt(a)==')' ){
                cnt--;
            }
            if( cnt == 0)
                break;
        }
        if( cnt==0)
            formula="("+formula+")";
        return formula;
    }
    /**
     * Converts a simple operation (only two operands) on elements in an array to a function
     * @param first The first element of the operation
     * @param second The second element of the operation
     * @param op The operator to apply
     * @param offset The offset for the index in the array
     * @return The function resulting from the above parameters
     */
    public static Function<Double[],Double> decodeDoublesOp(String first, String second, String op, int offset ){

        final Double db1;
        final int i1;
        final Double db2 ;
        final int i2;

        try{
            if(NumberUtils.isCreatable(first) ) {
                db1 = NumberUtils.createDouble(first);
                i1=-1;
            }else{
                db1=null;
                int index = NumberUtils.createInteger( first.substring(1));
                i1 = first.startsWith("o")?index:index+offset;
            }
            if(NumberUtils.isCreatable(second) ) {
                db2 = NumberUtils.createDouble(second);
                i2=-1;
            }else{
                db2=null;
                int index = NumberUtils.createInteger( second.substring(1));
                i2 = second.startsWith("o")?index:index+offset;
            }
        }catch( NumberFormatException e){
            Logger.error("Something went wrong decoding: "+first+" or "+second);
            return null;
        }

        Function<Double[],Double> proc=null;
        switch( op ){
            case "+":
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> db1+db2;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> x[i1]+db2;
                    } else if (db1 != null) { // meaning first is a number and second an index
                        proc = x -> db1+x[i2];
                    } else { // meaning both indexes
                        proc = x -> x[i1]+x[i2];
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "-":
                try{
                    if( db1!=null && db2!=null ){ // meaning both numbers
                        proc = x -> db1-db2;
                    }else if( db1==null && db2!=null){ // meaning first is an index and second a number
                        proc = x -> x[i1]-db2;
                    }else if(db1 != null){ // meaning first is a number and second an index
                        proc = x -> db1-x[i2];
                    }else{ // meaning both indexes
                        proc = x -> x[i1]-x[i2];
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "*":
                try{
                    if( db1!=null && db2!=null ){ // meaning both numbers
                        proc = x -> db1*db2;
                    }else if( db1==null && db2!=null){ // meaning first is an index and second a number
                        proc = x -> x[i1]*db2;
                    }else if(db1 != null){ // meaning first is a number and second an index
                        proc = x -> db1*x[i2];
                    }else{ // meaning both indexes
                        proc = x -> x[i1]*x[i2];
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;

            case "/": // i0/25
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> db1/db2;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> x[i1]/db2;
                    } else if (db1 != null) { //  meaning first is a number and second an index
                        proc = x -> db1/x[i2];
                    } else { // meaning both indexes
                        proc = x ->x[i1]/x[i2];
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;

            case "%": // i0%25
                try{
                    if( db1!=null && db2!=null ){ // meaning both numbers
                        proc = x -> db1%db2;
                    }else if( db1==null && db2!=null){ // meaning first is an index and second a number
                        proc = x -> x[i1]%db2;
                    }else if(db1 != null){ //  meaning first is a number and second an index
                        proc = x -> db1%x[i2];
                    }else{ // meaning both indexes
                        proc = x -> x[i1]%x[i2];
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "^": // i0^2
                try{
                    if( db1!=null && db2!=null ){ // meaning both numbers
                        proc = x -> Math.pow(db1,db2);
                    }else if( db1==null && db2!=null){ // meaning first is an index and second a number
                        if( db2.compareTo(0.5) == 0){ // root
                            proc = x -> Math.sqrt(x[i1]);
                        }else{
                            proc = x -> Math.pow(x[i1],db2);
                        }

                    }else if(db1 != null){ //  meaning first is a number and second an index
                        proc = x -> Math.pow(db1,x[i2]);
                    }else{ // meaning both indexes
                        proc = x -> Math.pow(x[i1],x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "scale": // i0/25
                try{
                    if( db1!=null && db2!=null ){ // meaning both numbers
                        proc = x -> Tools.roundDouble(db1,db2.intValue());
                    }else if( db1==null && db2!=null){ // meaning first is an index and second a number
                        proc = x -> Tools.roundDouble(x[i1],db2.intValue());
                    }else if(db1 != null){ //  meaning first is a number and second an index
                        proc = x -> Tools.roundDouble(db1,x[i2].intValue());
                    }else{ // meaning both indexes
                        proc = x -> Tools.roundDouble(x[i1],x[i2].intValue());
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "diff":
                try{
                    if( db1!=null && db2!=null ){ // meaning both numbers
                        proc = x -> Math.abs(db1-db2);
                    }else if( db1==null && db2!=null){ // meaning first is an index and second a number
                        proc = x -> Math.abs(x[i1]-db2);
                    }else if(db1 != null){ //  meaning first is a number and second an index
                        proc = x -> Math.abs(db1-x[i2]);
                    }else{ // meaning both indexes
                        proc = x -> Math.abs(x[i1]-x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "ln":
                try{
                    if( db1!=null && db2!=null ){ // meaning both numbers
                        Logger.error("Todo - ln bd,bd");
                        proc = x -> Math.log(db2);
                    }else if( db1==null && db2!=null){ // meaning first is an index and second a number
                        proc = x -> Math.log(db2);
                    }else if(db1 != null){ //  meaning first is a number and second an index
                        proc = x -> Math.log(x[i2]);
                    }else{ // meaning both indexes
                        proc = x -> Math.log(x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            default:Logger.error("Unknown operand: "+op); break;
        }
        return proc;
    }

    /**
     * Convert a delimited string to BigDecimals arra where possible, fills in null if not
     * @param list The delimited string
     * @param delimiter The delimiter to use
     * @return The resulting array
     */
    public static BigDecimal[] toBigDecimals(String list, String delimiter, int maxIndex ){
        String[] split = list.split(delimiter);
        if( maxIndex==-1)
            maxIndex=split.length-1;

        var bds = new BigDecimal[maxIndex+1];

        int nulls=0;
        for( int a=0;a<=maxIndex;a++){
            if( NumberUtils.isCreatable(split[a])) {
                try {
                    bds[a] = NumberUtils.createBigDecimal(split[a]);
                }catch(NumberFormatException e) {
                    bds[a] = new BigDecimal( NumberUtils.createBigInteger(split[a]));
                }
            }else{
                bds[a] = null;
                nulls++;
            }
        }
        return nulls==bds.length?null:bds;
    }

    /**
     * Convert a delimited string to an array of doubles, inserting null where conversion is not possible
     * @param list The delimited string
     * @param delimiter The delimiter to use
     * @return The resulting array
     */
    public static Double[] toDoubles(String list, String delimiter ){
        String[] split = list.split(delimiter);
        var dbs = new Double[split.length];
        int nulls=0;

        for( int a=0;a<split.length;a++){
            if( NumberUtils.isCreatable(split[a])) {
                try {
                    dbs[a] = NumberUtils.createDouble(split[a]);
                }catch(NumberFormatException e) {
                    // hex doesn't go wel to double...
                    dbs[a] = NumberUtils.createBigInteger(split[a]).doubleValue();
                }
            }else{
                dbs[a] = null;
                nulls++;
            }
        }
        return nulls==dbs.length?null:dbs;
    }
    /**
     * Convert a 12bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned8bit( int ori ){
        if( ori>0x80 ){ //two's complement
            ori = -1*((ori^0xFF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 12bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned10bit( int ori ){
        if( ori>0x200 ){ //two's complement
            ori = -1*((ori^0x3FF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 12bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned12bit( int ori ){
        if( ori>0x800 ){ //two's complement
            ori = -1*((ori^0xFFF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 16bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned16bit( int ori ){
        if( ori>0x8000 ){ //two's complement
            ori = -1*((ori^0xFFFF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 20bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned20bit( int ori ){
        if( ori>0x80000 ){ //two's complement
            ori = -1*((ori^0xFFFFF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 24bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned24bit( int ori ){
        if( ori>0x800000 ){ //two's complement
            ori = -1*((ori^0xFFFFFF) + 1);
        }
        return ori;
    }

    /**
     * Method that does a crc checksum for the given nmea string
     *
     * @param nmea The string to do the checksum on
     * @return True if checksum is ok
     */
    public static boolean doNMEAChecksum(String nmea) {
        int checksum = 0;
        for (int i = 1; i < nmea.length() - 3; i++) {
            checksum = checksum ^ nmea.charAt(i);
        }
        return nmea.endsWith(Integer.toHexString(checksum).toUpperCase());
    }

    /**
     * Calculates the nmea checksum for the given string and retunrs it
     * @param nmea The data to calculate the checksum from
     * @return The calculated hex value
     */
    public static String getNMEAchecksum(String nmea) {
        int checksum = 0;
        for (int i = 1; i < nmea.length(); i++) {
            checksum = checksum ^ nmea.charAt(i);
        }
        String hex = Integer.toHexString(checksum).toUpperCase();
        if (hex.length() == 1)
            hex = "0" + hex;
        return hex;
    }

    /**
     * Calculate the MD5 checksum of a file
     *
     * @param file The path to the file
     * @return The calculated checksum in ascii
     */
    public static String calculateMD5(Path file) {
        MessageDigest md;
        Logger.info("Calculating MD5 for " + file.toString());

        try {
            md = MessageDigest.getInstance("MD5");
            md.update(Files.readAllBytes(file));
            byte[] digest = md.digest();
            return DatatypeConverter.printHexBinary(digest);
        } catch (NoSuchAlgorithmException | IOException e) {
            Logger.error(e);
            return "";
        }
    }

    /**
     * Calculate the CRC16 according to the modbus spec
     *
     * @param data The data to calculate this on
     * @param append If true the crc will be appended to the data, if not only crc will be returned
     * @return Result based on append
     */
    public static byte[] calcCRC16_modbus(byte[] data, boolean append) {
        byte[] crc = calculateCRC16(data, data.length, 0xFFFF, 0xA001);
        if (!append)
            return crc;

        return ByteBuffer.allocate(data.length+crc.length)
                .put( data )
                .put(crc,0,crc.length)
                .array();
    }

    /**
     * Calculate CRC16 of byte data
     * @param data The data to calculate on
     * @param cnt Amount of data to process
     * @param start The value to start from
     * @param polynomial Which polynomial to use
     * @return An array containing remainder and dividend
     */
    public static byte[] calculateCRC16(byte[] data, int cnt, int start, int polynomial) {

        for (int pos = 0; pos < cnt; pos++) {
            start ^= (data[pos] & 0xFF);
            for (int x = 0; x < 8; x++) {
                boolean wasOne = start % 2 == 1;
                start >>>= 1;
                if (wasOne) {
                    start ^= polynomial;
                }
            }
        }
        return new byte[]{ (byte) Integer.remainderUnsigned(start, 256), (byte) Integer.divideUnsigned(start, 256) };
    }

    /**
     * Calculate the standard deviation
     *
     * @param set      The set to calculate the stdev from
     * @param decimals The amount of decimals
     * @return Calculated Standard Deviation
     */
    public static double calcStandardDeviation(ArrayList<Double> set, int decimals) {
        double sum = 0, sum2 = 0;
        int offset = 0;
        int size = set.size();

        if( size == 0 )
            return 0;

        for (int a = 0; a < set.size(); a++) {
            double x = set.get(a);
            if (Double.isNaN(x) || Double.isInfinite(x)) {
                set.remove(a);
                a--;
            }
        }

        if (size != set.size()) {
            Logger.error("Numbers in set are NaN or infinite, lef " + set.size()
                    + " of " + size + " elements");
        }
        for (double d : set) {
            sum += d;
        }

        double mean = sum / (set.size() - offset);
        for (double d : set) {
            sum2 += (d - mean) * (d - mean);
        }
        return Tools.roundDouble(Math.sqrt(sum2 / set.size()), decimals);
    }

    /**
     * Performes a second order A*x²2 + B*x + C computation in which A, B and C are
     * calibration coefficients
     *
     * @param cal      The calibration coëfficients
     * @param hex      The x in the formula in hexadecimal representation
     * @param decimals How many decimals in the result
     * @return Calculated value
     */
    public static double calc2ndOrder(BigDecimal[] cal, String hex, int decimals) {
        return calc2ndOrder( cal, Long.decode(hex), decimals);
    }

    /**
     * Performes a second order A*x²2 + B*x + C computation in which A, B and C are
     * calibration coefficients
     *
     * @param cal      The calibration coëfficients
     * @param dec      The x in the formula in decimal form
     * @param decimals How many decimals in the result
     * @return Calculated value
     */
    public static double calc2ndOrder(BigDecimal[] cal, double dec, int decimals) {
        if (cal == null) {
            Logger.error("Bad Calibrations values given");
            return -999;
        }
        try {
            BigDecimal bd = BigDecimal.valueOf(dec);
            BigDecimal bd2 = bd.pow(2);
            BigDecimal x2 = bd2.multiply(cal[0]);
            BigDecimal x1 = bd.multiply(cal[1]);
            BigDecimal result = x2.add(x1).add(cal[2]);
            return Tools.roundDouble(result.doubleValue(), decimals);
        } catch (NumberFormatException e) {
            Logger.error("Bad value received for 2nd order conversion: " + dec);
            return -999;
        }
    }

    /**
     * Performes a third order A*x³ + B*x² + C*x + D computation in which A, B, C and D are
     * calibration coefficients
     *
     * @param cal      The calibration coëfficients
     * @param dec      The x in the formula in decimal form
     * @param decimals How many decimals in the result
     * @return Calculated value
     */
    public static double calc3rdOrder(BigDecimal[] cal, double dec, int decimals) {
        if (cal == null) {
            Logger.error("Bad Calibrations values given");
            return -999;
        }
        try {
            BigDecimal bd = BigDecimal.valueOf(dec);
            BigDecimal bd2 = bd.pow(2);
            BigDecimal bd3 = bd.pow(3);

            BigDecimal x3 = bd3.multiply(cal[0]);
            BigDecimal x2 = bd2.multiply(cal[1]);
            BigDecimal x1 = bd.multiply(cal[2]);
            BigDecimal result = x3.add(x2).add(x1).add(cal[3]);
            return Tools.roundDouble(result.doubleValue(), decimals);
        } catch (NumberFormatException e) {
            Logger.error("Bad value received for 2nd order conversion: " + dec);
            return -999;
        }
    }

    /**
     * Convert the BCB representation of a number to the actual number
     * @param bcd The Binary coded digital value
     * @return A standard integer
     */
    public static int bcdToNumeric( int bcd ){
        int ten=bcd/16;
        int one=bcd%16;
        return ten*10+one;
    }

    /**
     * Convert the number to BCB representation eg. 25 to 0x25
     * @param number The number to convert
     * @return An integer BCD formatted
     */
    public static int numericToBCD( int number ){
        return (number/10)*16+number%10;
    }
}
