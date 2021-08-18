package util.taskblocks;

import org.apache.commons.lang3.StringUtils;
import org.tinylog.Logger;
import util.data.DataProviding;
import util.math.MathUtils;
import util.tools.Tools;

import java.util.ArrayList;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class CheckBlock extends AbstractBlock{

    DataProviding dp;
    ArrayList<Function<Double[],Double>> steps = new ArrayList<>();
    int resultIndex;
    boolean negate = false;

    public CheckBlock(DataProviding dp,String set){
        this.dp=dp;
        this.ori=set;
    }
    public static CheckBlock prepBlock(DataProviding dp, String set){
        return new CheckBlock(dp,set);
    }
    public void setNegate(boolean neg){
        negate=neg;
    }

    public void nextOk(){

    }

    @Override
    public void nextFailed() {

    }

    @Override
    public boolean start() {
        Double[] work= new Double[steps.size()+sharedMem.size()];
        for (int a = 0; a < sharedMem.size();a++ ){
            work[steps.size()+a]=sharedMem.get(a).getValue();
        }
        for( int a=0;a<steps.size();a++)
            work[a]=steps.get(a).apply(work);
        var pass = Double.compare(work[resultIndex],0.0)>0;
        pass = negate?!pass:pass;
        if( pass ) {
            doNext();
            parentBlock.ifPresent( TaskBlock::nextOk );
        }else{
            parentBlock.ifPresent( TaskBlock::nextFailed );
        }
        Logger.info( "Result of ori: "+pass);
        return pass;
    }

    public boolean build(){
        String exp = ori;

        // Fix the flag/issue and diff?
        Pattern words = Pattern.compile("[!a-zA-Z]+[_:0-9]*[a-zA-Z]+\\d*");
        var found = words.matcher(ori).results().map(MatchResult::group).collect(Collectors.toList());

        for( var comp : found ) {
            // Fixes flag:, !flag and issue:/!issue:
            if( comp.contains("flag:")){
                String val = comp.split(":")[1];
                exp = exp.replace(comp,"{f:"+val+"}=="+(comp.startsWith("!")?"0":"1"));
            }else if( comp.startsWith("issue")){
                String val = comp.split(":")[1];
                exp = exp.replace(comp,"{i:"+val+"}=="+(comp.startsWith("!")?"0":"1"));
            }
        }
        //Figure out brackets?
        exp=Tools.parseExpression(exp); // rewrite to math symbols


        // Figure out the realtime stuff
        exp = dp.buildNumericalMem(exp,sharedMem,0);

        // Figure out the brackets?
        // First check if the amount of brackets is correct
        int opens = StringUtils.countMatches(exp,"(");
        int closes = StringUtils.countMatches(exp,")");
        if( opens!=closes)
            return false;

        exp = MathUtils.checkBrackets(exp); // Then make sure it has surrounding brackets

        // Next go through the brackets from left to right (inner)
        var subFormulas = new ArrayList<String>(); // List to contain all the sub-formulas

        while( exp.contains("(") ){ // Look for an opening bracket
            int close = exp.indexOf(")"); // Find the first closing bracket
            int look = close-1; // start looking from one position left of the closing bracket
            int open = -1; // reset the open position to the not found value

            while( look>=0 ){ // while we didn't traverse the full string
                if( exp.charAt(look)=='(' ){ // is the current char an opening bracket?
                    open = look; // if so, store this position
                    break;// and quite the loop
                }
                look --;//if not, decrement the pointer
            }
            if( open !=-1 ){ // if the opening bracket was found
                String part = exp.substring(open+1,close); // get the part between the brackets
                String piece = exp.substring(open,close+1);
                exp = exp.replace(piece,"$$");
                // Split part on && and ||

                var and_ors = part.split("[&|!]{2}",0);
                for( var and_or : and_ors) {
                    boolean reverse = and_or.startsWith("!");
                    var comps = MathUtils.extractCompare(and_or);
                    for (var c : comps) {
                        if( c.isEmpty()) {
                            Logger.info("Found !?");
                        }else if(c.matches("[io]+\\d+")||c.matches("\\d*[.]?\\d*")){
                                // just copy these?
                        }else {
                            int index = subFormulas.indexOf(c);
                            if (index == -1) {
                                subFormulas.add(c);    // split that part in the sub-formulas
                                index = subFormulas.size() - 1;
                            }
                            and_or = and_or.replace(c, "o" + index);
                            part = part.replace(c, "o" + index);
                        }
                    }
                    if(!(and_or.matches("[io]+\\d+")||and_or.matches("\\d*"))) {
                        subFormulas.add(and_or);
                        part = part.replace(and_or, "o" + (subFormulas.size() - 1));
                    }
                }
                part=part.replace("&&","*");
                part=part.replace("||","+");
                if(part.contains("!|"))
                    part="("+part.replace("!|","+")+")%2";

                exp=exp.replace("$$",part);
            }else{
                Logger.error("CheckBlock -> Didn't find opening bracket");
            }
        }
        if( exp.length()!=2)
            subFormulas.add(exp);
        resultIndex=subFormulas.size()-1;

        // Convert the subformulas to functions
        subFormulas.forEach( x -> {
            x=x.startsWith("!")?x.substring(1)+"==0":x;
            var parts = MathUtils.extractParts(x);
            try {
                steps.add(MathUtils.decodeDoublesOp(parts.get(0), parts.size() == 3 ? parts.get(2) : "", parts.get(1), subFormulas.size()));
            }catch( IndexOutOfBoundsException e){
                Logger.error("CheckBox error during steps adding: "+ e.getMessage());
            }
        });

        return true;
    }
    public String toString(){
        return "Check if "+ori;
    }
}
