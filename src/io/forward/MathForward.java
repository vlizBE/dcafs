package io.forward;

import das.DataProviding;
import das.DoubleVal;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.math.Calculations;
import util.math.MathFab;
import util.math.MathUtils;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;

public class MathForward extends AbstractForward {

    private String delimiter = ";";
    private String suffix="";

    private final ArrayList<Operation> ops = new ArrayList<>();
    private BigDecimal scratchpad = BigDecimal.ZERO;
    private boolean doCmd = false;
    HashMap<String,String> defs = new HashMap<>();

    public enum OP_TYPE{COMPLEX, SCALE, LN, SALINITY, SVC,TRUEWINDSPEED,TRUEWINDDIR}
    private ArrayList<DoubleVal> remoteVals;


    public MathForward(String id, String source, BlockingQueue<Datagram> dQueue, DataProviding dp){
        super(id,source,dQueue,dp);
    }
    public MathForward(Element ele, BlockingQueue<Datagram> dQueue, DataProviding dp){
        super(dQueue,dp);
        readFromXML(ele);
    }
    /**
     * Read a mathforward from an element in the xml
     * @param ele The element containing the math info
     * @return The MathForward created based on the xml element
     */
    public static MathForward readXML(Element ele, BlockingQueue<Datagram> dQueue, DataProviding dp ){
        return new MathForward( ele,dQueue,dp );
    }
    /**
     * Get the tag that is used for the child nodes, this way the abstract class can refer to it
     * @return The child tag for this forward, parent tag is same with added s
     */
    protected String getXmlChildTag(){
        return "math";
    }

    /**
     * Give data to this forward for processing
     * @param data The data received
     * @return True if given to a target afterwards, false if not
     */
    @Override
    protected boolean addData(String data) {
        String[] split = data.split(delimiter); // Split the data according to the delimiter
        BigDecimal[] bds;
        int remoteOffset=0;

        if( !remoteVals.isEmpty()) {
            var remoteBds = new BigDecimal[remoteVals.size()];
            remoteOffset=remoteBds.length;
            for (int a = 0; a < remoteBds.length;a++ ){
                remoteBds[a]=BigDecimal.valueOf(remoteVals.get(a).getValue());
            }
            bds = ArrayUtils.addAll(MathUtils.toBigDecimals(data,delimiter),remoteBds);
        }else{
            bds = MathUtils.toBigDecimals(data,delimiter); // Split the data and convert to bigdecimals
        }

        int oldBad = badDataCount;

        if( bds == null ){
            badDataCount++;
            Logger.error(id+" (mf)-> No valid numbers in the data: "+data+" after split on "+delimiter+ " "+badDataCount+"/"+MAX_BAD_COUNT);
        }

        ops.forEach( op -> {
            if( op!=null ) {
                var res = op.solve(bds);
                if (res == null) {
                    badDataCount++;
                    Logger.error(id + "(mf) -> Failed to process " + data + " ("+badDataCount+"/"+MAX_BAD_COUNT+")");
                }
            }else{
                badDataCount++;
                Logger.error(id + "(mf) -> Invalid op " + data+ " ("+badDataCount+"/"+MAX_BAD_COUNT+")");
            }
        } ); // Solve the operations with the converted data
        if( oldBad == badDataCount )
            badDataCount=0;
        if( badDataCount>=MAX_BAD_COUNT) {
            Logger.error(id+"(mf)-> Too many bad data received, no longer accepting data");
            return false;
        }
        if( badDataCount > 0)
            return true;

        StringJoiner join = new StringJoiner(delimiter); // prepare a joiner to rejoin the data
        for( int a=0;a<(bds.length-remoteOffset);a++){
            join.add( bds[a]!=null?bds[a].toPlainString():split[a]); // if no valid bd is found, use the original data
        }

        // append suffix
        String result;
        if(!suffix.isEmpty()){
            // Append the nmea checksum
            if( suffix.equalsIgnoreCase("nmea")){
                result=join.toString()+"*"+MathUtils.getNMEAchecksum(join.toString());
            }else{
                Logger.error(getID()+" (mf)-> No such suffix "+suffix);
                result=join.toString();
            }
        }else{
            result=join.toString();
        }

        if( debug ){ // extra info given if debug is active
            Logger.info(getID()+" -> Before: "+data); // how the data looked before
            Logger.info(getID()+" -> After:  "+result); // after applying the operations
        }
        targets.removeIf( t-> !t.writeLine(result) ); // Send this data to the targets, remove those that refuse it

        if( !label.isEmpty() ){ // If the object has a label associated
            dQueue.add( Datagram.build(result).label(label).writable(this) ); // add it to the queue
        }
        if( log )
            Logger.tag("RAW").info( "1\t" + (label.isEmpty()?"void":label)+"|"+getID() + "\t" + result);

        // If there are no target, no label and no ops that build a command, this no longer needs to be a target
        if( targets.isEmpty() && label.isEmpty() && !doCmd && !log){
            valid=false;
            if( deleteNoTargets )
                dQueue.add( Datagram.system("mf:remove,"+id) );
            return false;
        }
        return true;
    }

    /**
     * Alter the delimiter used
     * @param deli The new delimiter to use, eg. \x09  or \t is also valid for a tab
     */
    public void setDelimiter( String deli ){
        if( deli.contains("\\")){
            delimiter = Tools.fromEscapedStringToBytes(deli);
        }else{
            delimiter = deli;
        }
    }
    /**
     * Set the value of this objects scratchpad, this can then be used in an op when referring to o0
     * @param value The new value for the scratchpad
     */
    public void setScratchpad( double value ){
        scratchpad=BigDecimal.valueOf(value);
        if(debug)
            Logger.info(id+" -> Scratchpad received "+value);
    }

    /**
     * Store this object's setup to the xml referred to with the given fab
     * @param fab The XMLfab pointing to where the parent xml should be
     * @return True if writing was successful
     */
    @Override
    public boolean writeToXML(XMLfab fab) {
        xml = fab.getXMLPath();
        xmlOk=true;

        fab.digRoot(getXmlChildTag()+"s"); // go down to <maths>
        if( fab.selectParent(getXmlChildTag(),"id",id).isEmpty() ){
            fab.comment("Some info on what the "+id+" "+getXmlChildTag()+" does");
            fab.addParent(getXmlChildTag()).attr("id",id);
        }

        fab.attr("delimiter",delimiter);
        if( !label.isEmpty())
            fab.attr("label",label);

        fab.clearChildren(); // Remove any existing

        if( sources.size()==1){
            fab.attr("src",sources.get(0));
        }else{
            fab.removeAttr("src");
            fab.comment("Sources go here");
            sources.forEach( src -> fab.addChild("src", src) );
        }
        if( !defs.isEmpty() ){
            defs.entrySet().forEach( def -> fab.addChild("def",def.getValue()).attr("ref",def.getKey()));
        }
        if( rulesString.size()==1 && sources.size()==1){
            fab.content("i"+rulesString.get(0)[1]+"="+rulesString.get(0)[2]);
        }else{
            fab.comment("Operations go here, possible types: complex (default) ,scale");
            rulesString.forEach( rule -> {
                fab.addChild("op",rule[2]).attr("index",rule[1]);
                if( !rule[0].equalsIgnoreCase("complex"))
                    fab.attr("type",rule[0]);
            } );
        }
        return fab.build()!=null;
    }

    /**
     * Read the settings for a mathforward from the given element
     * @param math The math child element
     * @return True if this was successful
     */
    @Override
    public boolean readFromXML(Element math) {

        if( !readBasicsFromXml(math) )
            return false;

        setDelimiter(XMLtools.getStringAttribute( math, "delimiter", delimiter));
        suffix = XMLtools.getStringAttribute(math,"suffix","");

        ops.clear();
        String content = math.getTextContent();

        if( content != null && XMLtools.getChildElements(math).isEmpty() ){
            var op = addComplex(content, XMLtools.getIntAttribute(math,"scale",-1));
            op.ifPresent( p -> p.cmd = XMLtools.getStringAttribute(math,"cmd",""));
        }
        defs.clear();
        XMLtools.getChildElements(math, "def")
                .forEach( def -> defs.put( def.getAttribute("ref"),def.getTextContent()));

        boolean oldValid=valid;

        XMLtools.getChildElements(math, "op")
                    .forEach( ops -> {
                        try {
                            addOperation(
                                    XMLtools.getIntAttribute(ops,"index",-1),
                                    XMLtools.getIntAttribute(ops,"scale",-1),
                                    fromStringToOPTYPE(XMLtools.getStringAttribute(ops, "type", "complex")),
                                    XMLtools.getStringAttribute(ops, "cmd", ""),
                                    ops.getTextContent());
                        }catch( NumberFormatException e){
                            Logger.error(id+" (mf)-> NumberformatException "+e.getMessage());
                        }
                    } );

        Collections.reverse(remoteVals); // reverse it so the first ones are at the end

        if( !oldValid && valid )// If math specific things made it valid
            sources.forEach( source -> dQueue.add( Datagram.build( source ).label("system").writable(this) ) );
        return true;
    }
    /**
     * Add an operation to this object
     * @param index Which index in the received array should the result be written to
     * @param type Which kind of operation, for now only COMPLEX, SCALE
     * @param cmd Send the result as part of a command
     * @param expression The expression to use
     * @return True if it was added
     */
    public Optional<Operation> addOperation(int index, int scale, OP_TYPE type, String cmd , String expression  ){

        if( index <0 && expression.contains("=")){
            var splt = expression.split("=");
            if( splt.length!=1) {
                if (splt[0].startsWith("i")) {
                    index = NumberUtils.toInt(splt[0].trim().substring(1), -1);
                    expression = splt[1].trim();
                }
            }
        }
        if( index == -1 ){
            Logger.error(id + " -> Bad/No index given");
            return Optional.empty();
        }

        Operation op;
        String[] indexes;
        switch( type ){
              case COMPLEX:
                    // Apply defs
                    String exp = expression;
                    for( var entry : defs.entrySet() ){
                        exp = exp.replace(entry.getKey(),entry.getValue());
                    }
                    var pairs = Tools.parseKeyValue(exp,true);
                    for( var p : pairs ) {
                        if (p.length == 2) {
                            if (p[0].equals("double") && dataProviding.hasDouble(p[1])) {
                                var d = dataProviding.getDoubleVal(p[1]);
                                if( remoteVals==null)
                                    remoteVals=new ArrayList<>();
                                int exist = remoteVals.indexOf(d);
                                if( exist == -1) {
                                    exp = exp.replace("{double:" + p[1] + "}", "i" + (MathUtils.DV_OFFSET + remoteVals.size()));
                                    remoteVals.add(d);
                                }else{
                                    exp = exp.replace("{double:" + p[1] + "}", "i" + (MathUtils.DV_OFFSET + exist));
                                }
                            }
                        }else{
                            Logger.error("Operation containing unknown pair: "+p[0]+":"+p[1]);
                        }
                    }
                    op = new Operation( expression, new MathFab(exp.replace(",",".")),index);
                    break;
            case SCALE: // round a number half up with the amount of digits specified
                    op = new Operation( expression, MathUtils.decodeBigDecimalsOp("i"+index,expression,"scale",0),index);
                    break;
            case LN:
                op = new Operation( expression, MathUtils.decodeBigDecimalsOp("i"+index,expression,"ln",0),index);
                break;
            case SALINITY:
                indexes = expression.split(",");
                if( indexes.length != 3 ){
                    Logger.error("Not enough info for salinity calculation");
                    return Optional.empty();
                }
                op = new Operation(expression, Calculations.procSalinity(indexes[0],indexes[1],indexes[2]), index);
                break;
            case SVC:
                indexes = expression.split(",");
                if( indexes.length != 3 ){
                    Logger.error("Not enough info for salinity calculation");
                    return Optional.empty();
                }
                op = new Operation(expression, Calculations.procSoundVelocity(indexes[0],indexes[1],indexes[2]), index);
                break;
            case TRUEWINDSPEED:
                indexes = expression.split(",");
                if( indexes.length != 5 ){
                    Logger.error("Not enough info for true wind calculation");
                    return Optional.empty();
                }
                op = new Operation(expression, Calculations.procTrueWindSpeed(indexes[0],indexes[1],indexes[2],indexes[3],indexes[4]), index);
                break;
            case TRUEWINDDIR:
                indexes = expression.split(",");
                if( indexes.length != 5 ){
                    Logger.error("Not enough info for true wind calculation");
                    return Optional.empty();
                }
                op = new Operation(expression, Calculations.procTrueWindDirection(indexes[0],indexes[1],indexes[2],indexes[3],indexes[4]), index);
                break;
            default:
                return Optional.empty();
        }
        op.setCmd(cmd);
        ops.add(op);

        if( scale != -1){ // Check if there's a scale op needed
            int pos =index;
            Function<BigDecimal[],BigDecimal> proc = x -> x[pos].setScale(scale, RoundingMode.HALF_UP);
            var p = new Operation( expression, proc,index);
            p.setCmd(cmd);  // this is the operation that should get the command
            op.cmd=""; // remove it from the other one
            ops.add( p );
            rulesString.add(new String[]{type.toString().toLowerCase(),""+index,"scale("+expression+", "+scale+")"});
        }else{
            rulesString.add(new String[]{type.toString().toLowerCase(),""+index,expression});
        }
        return Optional.ofNullable(ops.get(ops.size()-1)); // return the one that was added last
    }

    public Optional<Operation> addComplex( String op, int scale ){
        op=op.replace(" ",""); //remove spaces

        // Support ++ and --
        op=op.replace("++","+=1");
        op=op.replace("--","-=1");


        String[] split = op.split("\\D?[=]"); // i0+=1 -> [0]:i0  [1]:1

        if( split.length == 2){
            if( split[0].length()+split[1].length()+1 != op.length()){ // Support += -= *= and /= fe. i0+=1
                String[] spl = op.split("="); //[0]:i0+ [1]:1
                split[1]=spl[0]+split[1]; // split[1]=i0+1
            }
            int index = Tools.parseInt(split[0].substring(1),-1);
            if( index == -1 ){
                Logger.error( id+"(mf) -> Incorrect index "+op);
                return Optional.empty();
            }
            return addOperation(index,scale,OP_TYPE.COMPLEX,"",split[1]);
        }else{
            Logger.error(id+"(mf) -> Content in wrong format "+op);
        }
        return Optional.empty();
    }
    /**
     * Convert a string version of OP_TYPE to the enum
     * @return The resulting enum value
     */
    private OP_TYPE fromStringToOPTYPE(String optype) {
        switch(optype){
            case "complex": return OP_TYPE.COMPLEX;
            case "scale": return OP_TYPE.SCALE;
            case "ln": return OP_TYPE.LN;
            case "salinity": return OP_TYPE.SALINITY;
            case "svc": return OP_TYPE.SVC;
            case "truewinddir": return OP_TYPE.TRUEWINDDIR;
            case "truewindspeed": return OP_TYPE.TRUEWINDSPEED;
        }
        Logger.error("Invalid op type given, valid ones complex,scale");
        return null;
    }
    @Override
    public String getRules(){
        int index=0;
        StringJoiner join = new StringJoiner("\r\n");
        join.setEmptyValue(" -> No rules yet.");

        for( String[] x : rulesString ){
            join.add("\t"+(index++) +" : i"+x[1]+ " = "+x[2]);
        }
        return join.toString();
    }
    /**
     * Solve the operations based on the given data
     * @param data The data to use in solving the operations
     * @return The data after applying all the operations
     */
    public String solveFor(String data){
        String[] split = data.split(delimiter);

        BigDecimal[] bds = MathUtils.toBigDecimals(data,delimiter);
        ops.forEach( op -> op.solve(bds) );

        StringJoiner join = new StringJoiner(delimiter);
        for( int a=0;a<bds.length;a++){
            join.add( bds[a]!=null?bds[a].toPlainString():split[a]);
        }
        return join.toString();
    }

    /**
     * Storage class for everything related to an operation.
     * Contains the functions that
     */
    public class Operation {
        Function<BigDecimal[],BigDecimal> op=null; // for the scale type
        MathFab fab=null;    // for the complex type
        int index;      // index for the result
        String ori;     // The expression before it was decoded mainly for listing purposes
        String cmd =""; // Command in which to replace the $ with the result

        public Operation(String ori, Function<BigDecimal[],BigDecimal> op, int index ){
            this.op=op;
            this.index=index;
            this.ori=ori;
        }
        public Operation(String ori,int index){
            this.ori=ori;
        }
        public Operation(String ori, MathFab fab, int index ){
            this.fab=fab;
            this.index=index;
            this.ori=ori;
        }
        public void setCmd(String cmd){
            if( cmd.isEmpty())
                return;
            this.cmd=cmd;
            valid=true;
            doCmd = true;
        }
        public BigDecimal solve( BigDecimal[] data){
            BigDecimal bd=null;

            if( op != null ){
                if( data.length>index) {
                    try {
                        bd = op.apply(data);
                    }catch(NullPointerException e){
                        Logger.error("Nullpointer when processing for "+ori);
                        return null;
                    }
                }else{
                    Logger.error("Tried to do an op with to few elements in the array (data="+data.length+" vs index="+index);
                    return null;
                }
            }else if(fab!=null){
                fab.setDebug(debug);
                try {
                    bd = fab.solve(data, scratchpad);
                }catch ( ArrayIndexOutOfBoundsException | ArithmeticException | NullPointerException e){
                    Logger.error(id+" -> "+e.getMessage());
                    return null;
                }
            }else{ // Somehow figure out this needs to be nmea?
                return null;
            }
            if( bd == null ){
                Logger.error(id+" -> Failed to solve the received data");
                return null;
            }
            if( index!= -1 && index < data.length)
                data[index]=bd;

            if( !cmd.isEmpty()){
                dQueue.add( Datagram.system(cmd.replace("$",bd.toString())) );
            }
            return bd;
        }
    }
}
