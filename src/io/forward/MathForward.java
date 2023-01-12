package io.forward;

import util.data.RealtimeValues;
import util.data.RealVal;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.data.NumericVal;
import util.gis.GisTools;
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
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public class MathForward extends AbstractForward {

    private String delimiter = ",";
    private String suffix="";

    private final ArrayList<Operation> ops = new ArrayList<>();
    private boolean doCmd = false;
    private boolean doUpdate=false;
    HashMap<String,String> defines = new HashMap<>();

    public enum OP_TYPE{COMPLEX, SCALE, LN, SALINITY, SVC,TRUEWINDSPEED,TRUEWINDDIR,UTM,GDC}
    private ArrayList<NumericVal> referencedNums = new ArrayList<>();
    private int highestI=-1;

    public MathForward(String id, String source, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals){
        super(id,source,dQueue,rtvals);
        valid = rtvals!=null;
    }
    public MathForward(Element ele, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals){
        super(dQueue,rtvals);
        valid = readFromXML(ele);
    }
    public MathForward(BlockingQueue<Datagram> dQueue, RealtimeValues rtvals){
        super(dQueue,rtvals);
        valid = rtvals!=null;
    }
    /**
     * Read a mathForward from an element in the xml
     * @param ele The element containing the math info
     * @return The MathForward created based on the xml element
     */
    public static MathForward fromXML(Element ele, BlockingQueue<Datagram> dQueue, RealtimeValues rtvals ){
        return new MathForward( ele,dQueue,rtvals );
    }
    /**
     * Alter the delimiter used
     * @param deli The new delimiter to use, fe. \x09  or \t is also valid for a tab
     */
    public void setDelimiter( String deli ){
        if( deli.contains("\\")){
            delimiter = Tools.fromEscapedStringToBytes(deli);
        }else{
            delimiter = deli;
        }
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
    /* ***************************************** X M L ************************************************************ */
    /**
     * Get the tag that is used for the child nodes, this way the abstract class can refer to it
     * @return The child tag for this forward, parent tag is same with added s
     */
    protected String getXmlChildTag(){
        return "math";
    }
    /**
     * Read the settings for a mathForward from the given element
     * @param math The math child element
     * @return True if this was successful
     */
    @Override
    public boolean readFromXML(Element math) {

        if( !readBasicsFromXml(math) )
            return false;

        // Reset the references
        if( referencedNums!=null)
            referencedNums.clear();

        highestI=-1;

        setDelimiter(XMLtools.getStringAttribute( math, "delimiter", delimiter));
        suffix = XMLtools.getStringAttribute(math,"suffix","");
        defines.clear();
        ops.clear();
        String content = math.getTextContent();

        if( content != null && XMLtools.getChildElements(math).isEmpty() ){
            if( findReferences(content) ){
                var op = addStdOperation(
                        content,
                        XMLtools.getIntAttribute(math,"scale",-1),
                        XMLtools.getStringAttribute(math,"cmd","")
                );
                if(op.isEmpty()){
                    Logger.error("No valid operation found in: "+content);
                    return false;
                }
            }else {
                return false;
            }
        }

        XMLtools.getChildElements(math, "def")
                .forEach( def -> defines.put( def.getAttribute("ref"),def.getTextContent()));

        boolean oldValid=valid;
        for( var ops : XMLtools.getChildElements(math, "op") ){
            if( !findReferences(ops.getTextContent()))
                return false;
        }

        XMLtools.getChildElements(math, "op")
                .forEach( ops -> {
                    try {
                        var type= fromStringToOPTYPE(XMLtools.getStringAttribute(ops, "type", "complex"));
                        switch (Objects.requireNonNull(type)) {
                            case COMPLEX -> addStdOperation(
                                    ops.getTextContent(),
                                    XMLtools.getIntAttribute(ops, "scale", -1),
                                    XMLtools.getStringAttribute(ops, "cmd", "")
                            );
                            case LN, SALINITY, SVC, TRUEWINDSPEED, TRUEWINDDIR, UTM, GDC -> addOperation(
                                    XMLtools.getStringAttribute(ops, "index", "-1"),
                                    XMLtools.getIntAttribute(ops, "scale", -1),
                                    type,
                                    XMLtools.getStringAttribute(ops, "cmd", ""),
                                    ops.getTextContent());
                            default -> Logger.error("Bad type " + type);
                        }

                    }catch( NumberFormatException e){
                        Logger.error(id+" (mf)-> Number format Exception "+e.getMessage());
                    }
                } );

        if( !oldValid && valid )// If math specific things made it valid
            sources.forEach( source -> dQueue.add( Datagram.build( source ).label("system").writable(this) ) );
        return true;
    }
    /**
     * Store this object's setup to the xml referred to with the given fab
     *
     * @param fab The XMLfab pointing to where the parent xml should be
     */
    @Override
    public void writeToXML(XMLfab fab) {
        xml = fab.getXMLPath();
        xmlOk=true;

        fab.digRoot(getXmlChildTag()+"s"); // go down to <maths>
        if( fab.selectChildAsParent(getXmlChildTag(),"id",id).isEmpty() ){
            fab.comment("Some info on what the "+id+" "+getXmlChildTag()+" does");
            fab.addParentToRoot(getXmlChildTag()).attr("id",id);
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
        if( !defines.isEmpty() ){
            defines.forEach((key, value) -> fab.addChild("def", value).attr("ref", key));
        }
        if( rulesString.size()==1 && sources.size()==1){
            if( rulesString.get(0)[2].startsWith("i"+rulesString.get(0)[1]+"=")){
                fab.content(rulesString.get(0)[2]);
            }else{
                fab.content("i"+rulesString.get(0)[1]+"="+rulesString.get(0)[2]);
            }

        }else{
            fab.comment("Operations go here, possible types: complex (default) ,scale");
            rulesString.forEach( rule -> {
                fab.addChild("op",rule[2]);
                if( !rule[0].equalsIgnoreCase("complex"))
                    fab.attr("type",rule[0]);
            } );
        }
        fab.build();
    }
    /**
     * Give data to this forward for processing
     * @param data The data received
     * @return True if given to a target afterwards, false if not
     */
    @Override
    protected boolean addData(String data) {

        // First check if the operations are actually valid
        if( !valid ){
            showError("Not processing data because the operations aren't valid");
            return true;
        }

        String[] split = data.split(delimiter); // Split the data according to the delimiter

        // Then make sure there's enough items in split
        if( split.length < highestI+1){ // Need at least one more than the highestI (because it starts at 0)
            showError("Need at least "+(highestI+1)+" items after splitting: "+data+ ", got "+split.length+" (bad:"+badDataCount+")");
            return true; // Stop processing
        }
        // Convert the split data to bigdecimals
        BigDecimal[] bds = makeBDArray(split);

        // First do a global check, if none of the items is a number, no use to keep trying
        if( bds == null ){
            showError("No valid numbers in the data: "+data+" after split on "+delimiter+ " "+ " (bad:"+badDataCount+")");
            return true;
        }
        // We know that the 'highest needed index' needs to actually be a number
        if( bds[highestI]==null){
            showError(" (mf)-> No valid highest I value in the data: "+data+" after split on "+delimiter+ " "+ " (bad:"+badDataCount+")");
            return true;
        }
        int oldBad = badDataCount; // now it's worthwhile to compare bad data count
        // After doing all possible initial test, do the math
        int cnt=0;
        for( var op : ops ){
            var res = op.solve(bds);
            if (res == null) {
                cnt++;
                showError(cnt==1,"(mf) -> Failed to process " + data + " for "+op.ori);
            }
        }
        if( cnt > 0 ) {
            return true;
        }
        // If we got to this point, processing went fine so reset badcounts
        if( badDataCount !=0 )
            Logger.info(id+" (mf) -> Executed properly after previous issues, resetting bad count" );
        badDataCount=0;

        StringJoiner join = new StringJoiner(delimiter); // prepare a joiner to rejoin the data
        for( int a=0;a<split.length;a++){
            if( a <= (highestI==-1?0:highestI) ) {
                join.add(bds[a] != null ? bds[a].toPlainString() : split[a]); // if no valid bd is found, use the original data
            }else{
                join.add(split[a]);
            }
        }

        // append suffix
        String result = switch( suffix ){
                            case "" -> join.toString();
                            case "nmea" -> join+"*"+MathUtils.getNMEAchecksum(join.toString());
                            default -> {
                                Logger.error(id+" (mf)-> No such suffix "+suffix);
                                yield join.toString();
                            }
        };

        if( debug ){ // extra info given if debug is active
            Logger.info(getID()+" -> Before: "+data);   // how the data looked before
            Logger.info(getID()+" -> After:  "+result); // after applying the operations
        }
        targets.removeIf( t-> !t.writeLine(result) ); // Send this data to the targets, remove those that refuse it

        if( !label.isEmpty() ){ // If the object has a label associated
            Double[] d = new Double[bds.length];
            for( int a=0;a<bds.length;a++)
                d[a]=bds[a]==null?null:bds[a].doubleValue();  // don't try to convert null
            dQueue.add( Datagram.build(result).label(label).writable(this).payload(d) ); // add it to the queue
        }
        if( log )
            Logger.tag("RAW").info( "1\t" + (label.isEmpty()?"void":label)+"|"+getID() + "\t" + result);

        // If there are no target, no label and no ops that build a command, this no longer needs to be a target
        if( noTargets() && !log){
            if( deleteNoTargets )
                dQueue.add( Datagram.system("mf:remove,"+id) );
            return false;
        }
        return true;
    }
    private boolean showError(String error){
        return showError(true,error);
    }
    private boolean showError(boolean count, String error){
        if( count)
            badDataCount++;
        if( badDataCount==1 && count) { // only need to do this the first time
            if(label.startsWith("generic"))
                dQueue.add(Datagram.build("corrupt").label(label).writable(this));
            targets.stream().filter( t -> t.getID().startsWith("editor")).forEach( t -> t.writeLine("corrupt:1"));
        }
        if( badDataCount < 6) {
            if( !error.isEmpty())
                Logger.error(id+" (mf) -> "+error);
            return true;
        }
        if( badDataCount % 60 == 0 ) {
            if(badDataCount < 900 || badDataCount%600==0){
                if( !error.isEmpty())
                    Logger.error(id+" (mf) -> "+error);
            }
            return true;
        }
        return false;
    }
    /* ************************************** ADDING OPERATIONS **************************************************** */
    /**
     * Add an operation to this object
     * @param cmd Send the result as part of a command
     * @param expression The expression to use
     * @return True if it was added
     */
    public Optional<Operation> addStdOperation( String expression, int scale, String cmd ){

        // Support ++ and --
        expression = expression.replace("++","+=1")
                               .replace("--","-=1")
                               .replace(" ",""); //remove spaces

        findReferences(expression);

        if( !expression.contains("=") ) {// If this doesn't contain a '=' it's no good
            if(expression.matches("i[0-9]{1,3}")){
                var op = new Operation( expression, NumberUtils.toInt(expression.substring(1),-1));
                op.setCmd(cmd);
                op.setScale(scale);
                rulesString.add(new String[]{"complex",""+NumberUtils.toInt(expression.substring(1)),expression});
                addOp(op);
                return Optional.of(op);
            }else {
                return Optional.empty();
            }
        }
        String exp = expression;
        var split = expression.split("[+-/*^]?=");

        if( split[0].length()+split[1].length()+1 != exp.length()){ // Support += -= *= and /= fe. i0+=1
            String[] spl = exp.split("="); //[0]:i0+ [1]:1
            split[1]=spl[0]+split[1]; // split[1]=i0+1
        }

        var ii = split[0].split(",");
        int index = Tools.parseInt(ii[0].substring(1),-1); // Check if it's in the first or only position
        if( index == -1 && ii.length==2){ // if not and there's a second one
            index = Tools.parseInt(ii[1].substring(1),-1); //check if it's in the second one
        }else if(ii.length==2){
            expression=expression.replace(split[0],ii[1]+","+ii[0]); //swap the {d to front
        }

        if( (ii[0].toLowerCase().startsWith("{d")||ii[0].toLowerCase().startsWith("{r"))&&ii.length==1) {
            if( split[1].matches("i[0-9]{1,3}")){
                var op = new Operation( expression, NumberUtils.toInt(split[1].substring(1),-1));
                op.setCmd(cmd);
                op.setScale(scale);

                if( addOp(op) )
                    rulesString.add(new String[]{"complex",""+NumberUtils.toInt(split[1].substring(1)),expression});
                return Optional.of(op);
            }else{
                index = -2;
            }

        }
        exp = split[1];

        if( index == -1 ){
            Logger.warn(id + " -> Bad/No index given in "+expression);
        }

        Operation op;

        for( var entry : defines.entrySet() ){ // Check for the defaults and replace
            exp = exp.replace(entry.getKey(),entry.getValue());
        }

        exp = replaceReferences(exp);
        if( exp.isEmpty() )
            return Optional.empty();

        if( NumberUtils.isCreatable(exp.replace(",","."))) {
            op = new Operation( expression, exp.replace(",","."),index);
        }else{
            var fab = MathFab.newFormula(exp.replace(",","."));
            if( fab.isValid() ) { // If the formula could be parsed
                op = new Operation(expression, fab, index); // create an operation
            }else{
                return Optional.empty(); // If not, return empty
            }
        }
        ops.add(op);

        op.setScale(scale);
        op.setCmd(cmd);

        rulesString.add(new String[]{"complex",""+index,expression});
        return Optional.ofNullable(ops.get(ops.size()-1)); // return the one that was added last
    }

    public void addOperation(String index, int scale, OP_TYPE type, String cmd , String expression  ){

        expression=expression.replace(" ",""); //remove spaces

        String exp = expression;

        if( index.equalsIgnoreCase("-1") ){
            Logger.warn(id + " -> Bad/No index given in '"+cmd+"'|"+expression+" for "+type);
        }
        highestI = Math.max(highestI,NumberUtils.toInt(index,-1));

        exp=replaceReferences(exp);
        if( exp.isEmpty() )
            return;

        Operation op;
        String[] indexes = exp.split(",");

        switch( type ){
            case LN:
                op = new Operation( expression, MathUtils.decodeBigDecimalsOp("i"+index,exp,"ln",0),NumberUtils.toInt(index));
                break;
            case SALINITY:
                if( indexes.length != 3 ){
                    Logger.error(id+" (mf)-> Not enough args for salinity calculation");
                    return;
                }
                op = new Operation(expression, Calculations.procSalinity(indexes[0],indexes[1],indexes[2]), NumberUtils.toInt(index));
                break;
            case SVC:
                if( indexes.length != 3 ){
                    Logger.error(id+" (mf)-> Not enough args for soundvelocity calculation");
                    return;
                }
                op = new Operation(expression, Calculations.procSoundVelocity(indexes[0],indexes[1],indexes[2]), NumberUtils.toInt(index));
                break;
            case TRUEWINDSPEED:
                if( indexes.length != 5 ){
                    Logger.error(id+" (mf)-> Not enough args for True wind speed calculation");
                    return;
                }
                op = new Operation(expression, Calculations.procTrueWindSpeed(indexes[0],indexes[1],indexes[2],indexes[3],indexes[4]), NumberUtils.toInt(index));
                break;
            case TRUEWINDDIR:
                if( indexes.length != 5 ){
                    Logger.error(id+" (mf)-> Not enough args for True wind direction calculation");
                    return;
                }
                op = new Operation(expression, Calculations.procTrueWindDirection(indexes[0],indexes[1],indexes[2],indexes[3],indexes[4]), NumberUtils.toInt(index));
                break;
            case UTM:
                op = new Operation(expression, GisTools.procToUTM(indexes[0],indexes[1],
                        Arrays.stream(index.split(",")).map(NumberUtils::toInt).toArray( Integer[]::new)),-1);
                break;
            case GDC:
                op = new Operation(expression, GisTools.procToGDC(indexes[0],indexes[1],
                        Arrays.stream(index.split(",")).map(NumberUtils::toInt).toArray( Integer[]::new)),-1);
                break;
            default:
                return;
        }
        addOp(op);

        if( scale != -1){ // Check if there's a scale op needed
            Function<BigDecimal[],BigDecimal> proc = x -> x[NumberUtils.toInt(index)].setScale(scale, RoundingMode.HALF_UP);
            var p = new Operation( expression, proc, NumberUtils.toInt(index)).setCmd(cmd);
            if( addOp( p ))
                rulesString.add(new String[]{type.toString().toLowerCase(),""+index,"scale("+expression+", "+scale+")"});
        }else{
            op.setCmd(cmd);
            rulesString.add(new String[]{type.toString().toLowerCase(),""+index,expression});
        }
    }
    private boolean addOp( Operation op ){
        if( op == null ) {
            valid = false;
            Logger.error(id+"(mf) Tried to add a null operation, mathforward is invalid");
            return false;
        }
        if( op.isValid()){
            valid=false;
            Logger.error(id+"(mf) -> Tried to add an invalid op, mathformward is invalid");
        }
        ops.add(op);
        return true;
    }
    /**
     * Convert a string version of OP_TYPE to the enum
     * @return The resulting enum value
     */
    private OP_TYPE fromStringToOPTYPE(String optype) {
        switch(optype.toLowerCase()){

            case "scale": return OP_TYPE.SCALE;
            case "ln": return OP_TYPE.LN;
            case "salinity": return OP_TYPE.SALINITY;
            case "svc": return OP_TYPE.SVC;
            case "truewinddir": return OP_TYPE.TRUEWINDDIR;
            case "truewindspeed": return OP_TYPE.TRUEWINDSPEED;
            case "utm": return OP_TYPE.UTM;
            case "gdc": return OP_TYPE.GDC;
            default:
                Logger.error(id+"(mf) Invalid op type given, using default complex");
            case "complex": return OP_TYPE.COMPLEX;
        }
    }

    /**
     * Solve the operations based on the given data
     * @param data The data to use in solving the operations
     * @return The data after applying all the operations
     */
    public String solveFor(String data){

        String[] split = data.split(delimiter);

        BigDecimal[] bds = makeBDArray(split);

        ops.forEach( op -> op.solve(bds) );

        StringJoiner join = new StringJoiner(delimiter); // prepare a joiner to rejoin the data
        for( int a=0;a<split.length;a++){
            if( a <= highestI ) {
                join.add(bds[a] != null ? bds[a].toPlainString() : split[a]); // if no valid bd is found, use the original data
            }else{
                join.add(split[a]);
            }
        }
        return join.toString();
    }

    /**
     * Method to use all the functionality but without persistence
     * @param op The formula to compute fe. 15+58+454/3 or a+52 if 'a' was defined
     * @return The result
     */
    public double solveOp( String op ){
        ops.clear();rulesString.clear();

        var opt = addStdOperation("i0="+op,-1,"");
        if( opt.isEmpty())
            return Double.NaN;
        return NumberUtils.toDouble(solveFor("0"),Double.NaN);
    }
    /* ************************************* R E F E R E N C E S *************************************************** */

    /**
     * Create a static numericalval
     * @param key The id  to use
     * @param val The value
     */
    public void addNumericalRef( String key, double val){
        if( referencedNums==null)
            referencedNums=new ArrayList<>();
        for( var v : referencedNums ) {
            if (v.id().equalsIgnoreCase("matrix_" + key)) {
                v.updateValue(val);
                return;
            }
        }
        referencedNums.add( RealVal.newVal("matrix",key).value(val) );
    }
    /**
     * Build the BigDecimal array based on received data and the local references.
     * From the received data only the part that holds used 'i's is converted (so if i1 and i5 is used, i0-i5 is taken)
     * @param data The data received, to be split
     * @return The created array
     */
    private BigDecimal[] makeBDArray( String[] data ){

        if( referencedNums!=null && !referencedNums.isEmpty()){
            var refBds = new BigDecimal[referencedNums.size()];

            for (int a = 0; a < referencedNums.size();a++ ){
                refBds[a]=referencedNums.get(a).toBigDecimal();
            }
            return ArrayUtils.addAll(MathUtils.toBigDecimals(data,highestI==-1?0:highestI),refBds);
        }else{
            return MathUtils.toBigDecimals(data,highestI==-1?0:highestI); // Split the data and convert to big decimals
        }
    }
    /**
     * Check the expression for references to:
     * - reals -> {r:id} or {real:id}
     * - flags -> {f:id} or {flag:id}
     * If found, check if those exist and if so, add them to the corresponding list
     *
     * @param exp The expression to check
     * @return True if everything went ok and all references were found
     */
    private boolean findReferences(String exp){

        // Find all the double/flag pairs
        var pairs = Tools.parseKeyValue(exp,true);
        if( referencedNums==null)
            referencedNums = new ArrayList<>();
        for( var p : pairs ) {
            if (p.length == 2) {
                switch (p[0]) {
                    case "d", "double", "r", "real" -> rtvals.getRealVal(p[1]).ifPresent(referencedNums::add);
                    case "i", "int" -> rtvals.getIntegerVal(p[1]).ifPresent(referencedNums::add);
                    case "f", "flag" -> rtvals.getFlagVal(p[1]).ifPresent(referencedNums::add);
                    default -> {
                        Logger.error(id + " (mf)-> Operation containing unknown pair: " + p[0] + " and " + p[1]);
                        return false;
                    }
                }
            }else{
                Logger.error(id+" (mf)-> Pair containing odd amount of elements: "+String.join(":",p));
            }
        }
        if(referencedNums!=null)
            referencedNums.trimToSize();

        // Find the highest used 'i' index
        var is = Pattern.compile("i[0-9]{1,2}")
                .matcher(exp)
                .results()
                .map(MatchResult::group)
                .sorted()
                .toArray(String[]::new);
        if( is.length==0 ) {
            Logger.warn(id+" (mf)->No i's found in "+exp);
        }else{
            highestI = Math.max(highestI,Integer.parseInt(is[is.length-1].substring(1)));
        }
        return true;
    }

    /**
     * Use the earlier found references and replace them with the corresponding index.
     * The indexes will be altered so that they match if the correct index of an array containing
     * - The received data split according to the delimiter up to the highest used index
     * - The realVals found
     * - The flagVals found

     * So if highest is 5 then the first double will be 6 and first flag will be 5 + size of double list + 1
     *
     * @param exp The expression to replace the references in
     * @return The altered expression or an empty string if something failed
     */
    private String replaceReferences( String exp ){
        // Find the pairs in the expression
        for( var p : Tools.parseKeyValue(exp,true) ) {
            if (p.length == 2) { // The pair should be an actual pair
                boolean ok=false; // will be used at the end to check if ok
                p[0]=p[0].toLowerCase();
                switch(p[0]){
                    case "d","double","r","real","f","flag":
                        for( int pos=0;pos<referencedNums.size();pos++ ){ // go through the known doubleVals
                            var d = referencedNums.get(pos);
                            if( d.id().equalsIgnoreCase(p[1])) { // If a match is found
                                exp = exp.replace("{" + p[0] + ":" + p[1] + "}", "i" + (highestI + pos + 1));
                                ok=true;
                                break;
                            }
                        }
                        break;
                    default:
                        Logger.error(id+" (mf)-> Operation containing unknown pair: "+String.join(":",p));
                        return "";
                }
                if(!ok){
                    Logger.error(id+" (mf)-> Didn't find a match when looking for "+String.join(":",p));
                    return "";
                }
            }else{
                Logger.error(id+" (mf)-> Pair containing to many elements: "+String.join(":",p));
                return "";
            }
        }
        return exp;
    }
    /* ************************************* O P E R A T I O N ***************************************************** */
    /**
     * Storage class for everything related to an operation.
     * Contains the functions that
     */
    public class Operation {
        Function<BigDecimal[],BigDecimal> op=null; // for the scale type
        MathFab fab=null;    // for the complex type

        int index;           // index for the result
        int scale=-1;
        String ori;          // The expression before it was decoded mainly for listing purposes
        String cmd ="";      // Command in which to replace the $ with the result
        NumericVal update;
        BigDecimal directSet;

        public Operation(String ori,int index){
            this.ori=ori;
            this.index=index;

            if( ori.contains(":") && ori.indexOf(":")<ori.indexOf("=") ) { // If this contains : it means it has a reference
                try {
                    String sub = ori.substring(ori.indexOf(":") + 1, ori.indexOf("}"));

                    if (ori.startsWith("{r")||ori.startsWith("{d")) {
                        rtvals.getRealVal(sub)
                                .ifPresent(dv -> {
                                    update = dv;
                                    doUpdate = true;
                                });
                        if (!doUpdate)
                            Logger.error("Asking to update {r:" + ori.substring(ori.indexOf(":") + 1, ori.indexOf("}") + 1) + " but doesn't exist");
                    } else if (ori.startsWith("{i")) {
                        rtvals.getIntegerVal(sub)
                                .ifPresent(iv -> {
                                    update = iv;
                                    doUpdate = true;
                                });
                        if (!doUpdate)
                            Logger.error("Asking to update {i:" + ori.substring(ori.indexOf(":") + 1, ori.indexOf("}") + 1) + " but doesn't exist");
                    }else{
                        Logger.error( "No idea what to do with "+ori);
                    }
                }catch(IndexOutOfBoundsException e ){
                    Logger.error( id+" (mf) -> Index out of bounds: "+e.getMessage());
                }
            }
        }
        public Operation(String ori, Function<BigDecimal[],BigDecimal> op, int index ){
            this(ori,index);
            this.op=op;
        }
        public Operation(String ori, MathFab fab, int index ){
            this(ori,index);
            if( fab.isValid())
                this.fab=fab;
        }
        public Operation(String ori, String value, int index ){
            this(ori,index);
            this.directSet = NumberUtils.createBigDecimal(value);
        }
        public boolean isValid(){
            return op!=null || fab!=null;
        }
        public void setScale( int scale ){
            this.scale=scale;
        }
        public Operation setCmd(String cmd){
            if( cmd.isEmpty())
                return this;
            this.cmd=cmd;
            valid=true;
            doCmd = true;

            if( ((cmd.startsWith("doubles:update")||cmd.startsWith("dv")) && cmd.endsWith(",$"))  ){
                String val = cmd.substring(8).split(",")[1];
                this.cmd = rtvals.getRealVal(val).map(dv-> {
                    update=dv;
                    doUpdate=true;
                    return "";
                } ).orElse(cmd);
            }
            return this;
        }
        public BigDecimal solve( BigDecimal[] data){
            BigDecimal bd;
            boolean changeIndex=true;
            if( op != null ) {
                if (data.length <= index){
                    showError(false,"(mf) -> Tried to do an op with to few elements in the array (data=" + data.length + " vs index=" + index);
                    return null;
                }
                try {
                    bd = op.apply(data);
                } catch (NullPointerException e) {
                    if (showError(false,"(mf) -> Null pointer when processing for " + ori)){
                        StringJoiner join = new StringJoiner(", ");
                        Arrays.stream(data).map(d -> "" + d).forEach(join::add);
                        Logger.error(getID() + "(mf) -> Data: " + join);
                    }
                    return null;
                }
            }else if(fab!=null){
                fab.setDebug(debug);
                fab.setShowError( showError(false,""));
                try {
                    var bdOpt = fab.solve(data);
                    if( bdOpt.isEmpty() ){
                        showError(false,"(mf) -> Failed to solve the received data");
                        return null;
                    }
                    bd=bdOpt.get();
                }catch ( ArrayIndexOutOfBoundsException | ArithmeticException | NullPointerException e){
                    showError(false,e.getMessage());
                    return null;
                }
            }else if( directSet!= null ){
                bd = directSet;
            }else if(index!=-1){
                if( data[index]==null){
                    showError(false," (mf) -> Index "+index+" in data is null");
                    return null;
                }
                bd = data[index];
                changeIndex=false;
            }else{
                return null;
            }

            if( scale != -1)
                bd=bd.setScale(scale,RoundingMode.HALF_UP);

            if( index>= 0 && index < data.length && changeIndex )
                data[index]=bd;

            if( update != null ) {
                update.updateValue(bd.doubleValue());
            }else if( !cmd.isEmpty()){
                dQueue.add(Datagram.system(cmd.replace("$", bd.toString())));
            }
            return bd;
        }
    }
    /* ************************************************************************************************************* */
    @Override
    public boolean noTargets(){
        return super.noTargets() && !doCmd && !doUpdate;
    }
}
