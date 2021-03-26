package util.math;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.tinylog.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class MathFab {

    ArrayList<Function<BigDecimal[],BigDecimal>> steps = new ArrayList<>();
    int offset=0;
    int resultIndex=-1;
    int requiredInputs=0;
    static final String[] ORDERED_OPS={"^","^","*","/","%","%","+","-"};
    static final String OPS_REGEX="\\+|/|\\*|-|\\^|%";
    boolean debug = false;

    public MathFab( String formula ){
        build(formula);
    }
    public MathFab( String formula, boolean debug ){
        this.debug=debug;
        build(formula);
    }
    public MathFab(){}
    public static MathFab newFormula( String formula ){
        return new MathFab(formula);
    }
    public static MathFab newFormula( String formula, boolean debug ){
        return new MathFab(formula,debug);
    }
    public void setDebug( boolean debug ){
        this.debug=debug;
    }
    public MathFab build(String formula ){
        steps.clear(); // reset the steps

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
            return this;
        }

        formula = checkBrackets(formula); // Then make sure it has surrounding brackets
        formula=formula.replace(" ",""); // But doesn't contain any spaces
        if( debug )
            Logger.info("Building: "+formula);
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
                subFormulas.addAll( split( part, subFormulas.size()) );    // split that part in the subformulas
                String piece = formula.substring(open,close+1); // includes the brackets
                // replace the sub part in the original formula with a reference to the last subformula
                formula=formula.replace(piece,"o"+(subFormulas.size()));
                if( debug )
                    Logger.info("=>Formula: "+formula);
            }else{
                Logger.error("Didn't find opening bracket");
            }
        }
        offset=subFormulas.size()+1; // To store the intermediate results, the array needs to hold space
        for( String[] sub : subFormulas ){ // now convert the subformulas into lambda's
            steps.add( MathUtils.decodeBigDecimals(sub[0],sub[1],sub[2],offset)); // and add it to the steps list
        }
        resultIndex = subFormulas.size();// note that the result of the formula will be in the that position
        return this;
    }
    public List<String[]> split( String formula, int indexOffset ){
        var result = new ArrayList<String[]>();

        formula=formula.replace("+-","-"); // adding a negative number is the same as subtracting it

        var parts = extractParts(formula);

        if( debug ){
            Logger.info("-> Splitting: "+formula);
        }
        indexOffset++;
        try {
            if (parts.size() == 3) {
                if (debug) {
                    Logger.info("  Sub: " + "o" + indexOffset + "=" + formula);
                }
                result.add(new String[]{parts.get(0), parts.get(2), parts.get(1)});
            } else {
                int oIndex = indexOffset;
                for (int a = 0; a < ORDERED_OPS.length; a += 2) {

                    int opIndex = getIndexOfOperand(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);

                    while (opIndex != -1) {
                        String res = parts.get(opIndex - 1) + parts.get(opIndex) + parts.get(opIndex + 1);
                        result.add(new String[]{parts.get(opIndex - 1), parts.get(opIndex + 1), parts.get(opIndex)});
                        parts.remove(opIndex);  // remove the operand
                        parts.remove(opIndex);  // remove the top part
                        parts.set(opIndex - 1, "o" + oIndex); // replace the bottom one

                        if (debug) {
                            Logger.info("  Sub: " + "o" + oIndex + "=" + res);
                        }
                        formula = formula.replace(res, "o" + oIndex++);
                        opIndex = getIndexOfOperand(parts, ORDERED_OPS[a], ORDERED_OPS[a + 1]);
                    }
                }
            }
        }catch( IndexOutOfBoundsException e){
            Logger.error("Index issue while processing "+formula);
            Logger.error(e);
        }
        return result;
    }
    private int getIndexOfOperand( List<String> data, String op1, String op2){
        int op1Index = data.indexOf(op1);
        int op2Index = data.indexOf(op2);
        int opIndex;

        if( op1Index==-1) { // first op can't be found, so it's e
            return op2Index;
        }
        if( op2Index==-1){
            return op1Index;
        }else{
            return Math.min( op1Index,op2Index);
        }
    }
    private List<String> extractParts( String formula ){
        formula = formula.replace("e","E");
        String alt = formula.replace("E-","e");
        alt = alt.replace("E+","E");

        String[] spl = alt.split(OPS_REGEX);

        String ops = alt.replaceAll("[a-zA-Z0-9]", "");
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
                full.add(spl[a].replace("e","E-"));
                // add the op
                if( b<ops.length())
                    full.add(""+ops.charAt(b));
                b++;
            }
        }
        return full;
    }
    private static String checkBrackets( String formula ){

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
    public BigDecimal solve( String data, String delimiter ){
        return solve( MathUtils.toBigDecimals(data,delimiter),BigDecimal.ZERO );
    }
    public double solveFor( double... val){
        var bds = new BigDecimal[val.length];
        for(int a=0;a<val.length;a++)
            bds[a]=BigDecimal.valueOf(val[a]);
        var bd = solve(bds,BigDecimal.ZERO);

        return bd.doubleValue();
    }
    public BigDecimal solve( BigDecimal[] data, BigDecimal scratchpad ){
        if( resultIndex == -1 ){
            Logger.error("No valid formula present");
            return null;
        }
        if( data == null ){
            Logger.error("Source data is null");
        }
        if( requiredInputs > data.length ){
            Logger.error("Not enough elements given, need at least "+requiredInputs+" but got "+data.length);
            return null;
        }

        BigDecimal[] total = ArrayUtils.addAll(new BigDecimal[offset],data);
        if(debug)
            Logger.info("Highest expected index: "+total.length+" from offset="+offset+" and data "+data.length);

        total[0]=scratchpad;
        int i=1;
        if( debug )
            Logger.info("0 : "+total[0]);
        for( var f : steps ){
            try{
                total[i] = f.apply(total);
                if( debug )
                    Logger.info(i +" : "+total[i]);
                i++;
            }catch (IndexOutOfBoundsException | NullPointerException e){
                Logger.error("Bad things when it was processed, array size "+data.length+" versus "+requiredInputs);
                int a=0;
                for( var big : data){
                    if( big!=null) {
                        Logger.error(a+" -> array:" + big.toString());
                    }else{
                        Logger.error(a+" -> array:null");
                    }
                    a++;
                }
                Logger.error(e);
                break;
            }

        }
        if( total[resultIndex] != null ) {
            if(debug)
                Logger.info("Result: " + total[resultIndex].doubleValue());
            return total[resultIndex];
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
