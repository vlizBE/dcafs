package util.taskblocks;

import org.apache.commons.lang3.StringUtils;
import org.tinylog.Logger;
import util.data.DataProviding;
import util.math.MathUtils;
import util.tools.Tools;

import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Function;

public class CheckBlock extends AbstractBlock{

    DataProviding dp;
    ArrayList<Function<Double[],Double>> steps = new ArrayList<>();
    int resultIndex;

    public CheckBlock(DataProviding dp){
        this.dp=dp;
    }
    public static CheckBlock prepBlock(DataProviding dp){
        return new CheckBlock(dp);
    }
    public void doNext() {
        next.forEach( n->n.start() );
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
        if( pass ) {
            doNext();
            parentBlock.ifPresent( TaskBlock::nextOk );
        }else{
            parentBlock.ifPresent( TaskBlock::nextFailed );
        }
        Logger.info( "Result of ori: "+pass);
        return pass;
    }

    public Optional<TaskBlock> build(TaskBlock prev, String set){
        ori=set;
        if( prev!=null && prev.hasSharedMem()) {
            sharedMem = prev.getSharedMem();
        }else {
            sharedMem=new ArrayList<>();
        }
        //Figure out brackets?
        set=Tools.parseExpression(set); // rewrite to math symbols
        // Figure out the realtime stuff
        set = dp.buildNumericalMem(set,sharedMem,0);

        // Figure out the brackets?
        // First check if the amount of brackets is correct
        int opens = StringUtils.countMatches(set,"(");
        int closes = StringUtils.countMatches(set,")");
        if( opens!=closes)
            return Optional.empty();

        set = MathUtils.checkBrackets(set); // Then make sure it has surrounding brackets

        // Next go through the brackets from left to right (inner)
        var subFormulas = new ArrayList<String>(); // List to contain all the sub-formulas

        while( set.contains("(") ){ // Look for an opening bracket
            int close = set.indexOf(")"); // Find the first closing bracket
            int look = close-1; // start looking from one position left of the closing bracket
            int open = -1; // reset the open position to the not found value

            while( look>=0 ){ // while we didn't traverse the full string
                if( set.charAt(look)=='(' ){ // is the current char an opening bracket?
                    open = look; // if so, store this position
                    break;// and quite the loop
                }
                look --;//if not, decrement the pointer
            }
            if( open !=-1 ){ // if the opening bracket was found
                String part = set.substring(open+1,close); // get the part between the brackets
                String piece = set.substring(open,close+1);
                set = set.replace(piece,"$$");
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

                set=set.replace("$$",part);

                // replace the sub part in the original set with a reference to the last sub-formula

                if( true )
                    Logger.info("=>Formula: "+set);
            }else{
                Logger.error("CheckBlock -> Didn't find opening bracket");
            }
        }
        if( set.length()!=2)
            subFormulas.add(set);
        resultIndex=subFormulas.size()-1;

        // Convert the subformulas to functions
        subFormulas.forEach( x -> {
            var parts = MathUtils.extractParts(x);
            steps.add( MathUtils.decodeDoublesOp(parts.get(0),parts.size()==3?parts.get(2):"",parts.get(1),subFormulas.size()) );
        });

        parentBlock = Optional.ofNullable(prev);
        parentBlock.ifPresentOrElse( tb->tb.addNext(this), ()->srcBlock=true);

        return Optional.of(this);
    }
    public void addNext(TaskBlock block) {
        next.add(block);
    }
}
