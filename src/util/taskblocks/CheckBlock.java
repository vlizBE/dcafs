package util.taskblocks;

import org.apache.commons.lang3.StringUtils;
import org.tinylog.Logger;
import util.data.DataProviding;
import util.math.MathUtils;
import util.tools.Tools;

import java.util.ArrayList;
import java.util.function.Function;

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

        //Figure out brackets?
        ori=Tools.parseExpression(ori); // rewrite to math symbols
        // Figure out the realtime stuff
        ori = dp.buildNumericalMem(ori,sharedMem,0);

        // Figure out the brackets?
        // First check if the amount of brackets is correct
        int opens = StringUtils.countMatches(ori,"(");
        int closes = StringUtils.countMatches(ori,")");
        if( opens!=closes)
            return false;

        ori = MathUtils.checkBrackets(ori); // Then make sure it has surrounding brackets

        // Next go through the brackets from left to right (inner)
        var subFormulas = new ArrayList<String>(); // List to contain all the sub-formulas

        while( ori.contains("(") ){ // Look for an opening bracket
            int close = ori.indexOf(")"); // Find the first closing bracket
            int look = close-1; // start looking from one position left of the closing bracket
            int open = -1; // reset the open position to the not found value

            while( look>=0 ){ // while we didn't traverse the full string
                if( ori.charAt(look)=='(' ){ // is the current char an opening bracket?
                    open = look; // if so, store this position
                    break;// and quite the loop
                }
                look --;//if not, decrement the pointer
            }
            if( open !=-1 ){ // if the opening bracket was found
                String part = ori.substring(open+1,close); // get the part between the brackets
                String piece = ori.substring(open,close+1);
                ori = ori.replace(piece,"$$");
                // Split part on && and ||

                var and_ors = part.split("[&|!]{2}",0);
                for( var and_or : and_ors) {
                    boolean reverse = and_or.startsWith("!");
                    var comps = MathUtils.extractCompare(and_or);
                    for (var c : comps) {
                        if( c.isEmpty()) {
                            Logger.info("Found !?");
                        }else if(c.matches("[io]+\\d+")||c.matches("\\d*")){
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

                ori=ori.replace("$$",part);

                // replace the sub part in the original set with a reference to the last sub-formula

                if( true )
                    Logger.info("=>Formula: "+ori);
            }else{
                Logger.error("CheckBlock -> Didn't find opening bracket");
            }
        }
        if( ori.length()!=2)
            subFormulas.add(ori);
        resultIndex=subFormulas.size()-1;

        // Convert the subformulas to functions
        subFormulas.forEach( x -> {
            var parts = MathUtils.extractParts(x);
            steps.add( MathUtils.decodeDoublesOp(parts.get(0),parts.size()==3?parts.get(2):"",parts.get(1),subFormulas.size()) );
        });

        return true;
    }
}
