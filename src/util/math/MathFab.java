package util.math;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.tinylog.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class MathFab {

    ArrayList<Function<BigDecimal[],BigDecimal>> steps = new ArrayList<>();
    int offset=0;
    int resultIndex=-1;
    int requiredInputs=0;
    boolean debug = false;
    String ori="";
    boolean valid;

    public MathFab( String formula ){
        valid=build(formula)!=null;
    }

    public static MathFab newFormula( String formula ){
        return new MathFab(formula);
    }

    /**
     * Enable or disable extra debug information
     * @param debug
     */
    public void setDebug( boolean debug ){
        this.debug=debug;
    }

    /**
     * Check if this mathfab is valid or failed the formula parsing
     * @return True if valid
     */
    public boolean isValid(){
        return valid;
    }
    /**
     * Parse the formula to functions
     * @param formula The formula to parse
     * @return This object or null if failed
     */
    private MathFab build(String formula ){
        steps.clear(); // reset the steps
        ori=formula;

        // First check if the amount of brackets is correct
        int opens = StringUtils.countMatches(formula,"(");
        int closes = StringUtils.countMatches(formula,")");

        var is = Pattern.compile("[i][0-9]{1,2}")
                .matcher(formula)
                .results()
                .map(MatchResult::group)
                .sorted()
                .toArray(String[]::new);
        if( is.length==0 ){
            requiredInputs = 0;
        }else{
            requiredInputs = 1+Integer.parseInt(is[is.length-1].substring(1));
        }

        if( opens != closes ){
            Logger.error("Brackets don't match, (="+opens+" and )="+closes);
            return null;
        }

        formula = MathUtils.checkBrackets(formula); // Then make sure it has surrounding brackets
        formula=formula.replace(" ",""); // But doesn't contain any spaces
        if( debug )
            Logger.info("Building: "+formula);
        // Next go through the brackets from left to right (inner)
        var subFormulas = new ArrayList<String[]>(); // List to contain all the sub-formulas

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
                var res = MathUtils.splitExpression( part, subFormulas.size()-1,debug);
                if( res.isEmpty()) {
                    Logger.error("Failed to build because of issues during "+part);
                    return null;
                }
                subFormulas.addAll( res );    // split that part in the sub-formulas
                String piece = formula.substring(open,close+1); // includes the brackets
                // replace the sub part in the original formula with a reference to the last sub-formula
                formula=formula.replace(piece,"o"+(subFormulas.size()-1));
                if( debug )
                    Logger.info("=>Formula: "+formula);
            }else{
                Logger.error("Didn't find opening bracket");
            }
        }

        offset=subFormulas.size(); // To store the intermediate results, the array needs to hold space
        for( String[] sub : subFormulas ){ // now convert the sub-formulas into lambda's
            var x = MathUtils.decodeBigDecimalsOp(sub[0],sub[1],sub[2],offset);
            if( x==null ){
                Logger.error("Failed to convert "+formula);
                return null;
            }
            steps.add( x ); // and add it to the steps list
        }
        resultIndex = subFormulas.size()-1;// note that the result of the formula will be in the that position
        return this;
    }

    /**
     * Solve the build equation based on the given data
     * @param data The data to use
     * @param delimiter The delimiter to split the data
     * @return The result
     */
    public BigDecimal solve( String data, String delimiter ){
        return solve( MathUtils.toBigDecimals(data,delimiter,-1) );
    }

    /**
     * Solve the build equation using the given values
     * @param val The values to use
     * @return The result
     */
    public double solveFor( double... val){
        var bds = new BigDecimal[val.length];
        for(int a=0;a<val.length;a++)
            bds[a]=BigDecimal.valueOf(val[a]);
        var bd = solve(bds);

        return bd.doubleValue();
    }

    /**
     * Solve the build equation using the given bigdecimals
     * @param data The bigDecimals used in the operation
     * @return Result of the operation
     * @throws ArrayIndexOutOfBoundsException Indicating lack of elements
     */
    public BigDecimal solve( BigDecimal[] data ) throws ArrayIndexOutOfBoundsException{
        if( resultIndex == -1 ){
            Logger.error("No valid formula present");
            return null;
        }
        if( data == null ){
            Logger.error("Source data is null");
            return null;
        }
        if( requiredInputs > data.length ){
            throw new ArrayIndexOutOfBoundsException("Not enough elements given, need at least "+requiredInputs+" but got "+data.length);
        }

        BigDecimal[] total = ArrayUtils.addAll(new BigDecimal[offset],data);
        if(debug)
            Logger.info("Highest expected index: "+total.length+" from offset="+offset+" and data "+data.length);

        int i=0;
        for( var f : steps ){ // Now go through all the steps in the calculation
            try{
                total[i] = f.apply(total); // store the result of the step in the corresponding field
                if( debug )
                    Logger.info(i +" : "+total[i]); // As extra debug information, put the result in the log
                i++;// increment the counter
            }catch (IndexOutOfBoundsException | NullPointerException e){
                Logger.error("Bad things when it was processed, array size "+data.length+" versus "+requiredInputs +" with step null?"+(f==null));
                Logger.error("Original formula: "+ori);

                for( int a=0;a<data.length;a++){
                    if( data[a]!=null) {
                        Logger.error(a+" -> array: " + data[a]);
                    }else{
                        Logger.error(a+" -> array: null");
                    }
                }
                Logger.error(e);
                return null;
            }
        }
        if( total[resultIndex] != null ) { // If the position in which the result should be isn't null
            if(debug)
                Logger.info("Result: " + total[resultIndex].doubleValue());
            return total[resultIndex]; // return this result
        }else{
            Logger.error("Something went wrong during calculation");
            return null;
        }
    }
    public static void test(){
        double d1 = MathFab.newFormula("(15*i0)/65+3*i1").solveFor(10.0,3.5);
        if( d1 != 12.80769231 ) {
            Logger.error("Not received expected result from first formula, got "+d1+" instead of 12.80769231")   ;
            return;
        }
        d1 = MathFab.newFormula("(15+i0)^2-16*i1+16+25+36+58+i2/5").solveFor(5,65,86);
        if( d1 != -487.8 ) {
            Logger.error("Not received expected result from second formula, got " + d1+" instead of -487.8");
            return;
        }
        d1 = MathFab.newFormula("i0*-5").solveFor(5,65,86);
        if( d1 != -25 ) {
            Logger.error("Not received expected result from third formula, got " + d1+" instead of -25");
            return;
        }
        Logger.info("All MathFab tests successful");
    }
}
