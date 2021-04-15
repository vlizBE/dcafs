package com.stream.forward;

import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.math.MathFab;
import util.math.MathUtils;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.function.Function;

public class MathForward extends AbstractForward {

    private String delimiter = ";";
    private final ArrayList<Operation> ops = new ArrayList<>();
    private BigDecimal scratchpad = BigDecimal.ZERO;
    private boolean doCmd = false;

    public enum OP_TYPE{COMPLEX,SCALE}

    public MathForward(String id, String source, BlockingQueue<Datagram> dQueue ){
        super(id,source,dQueue);
    }
    public MathForward(Element ele, BlockingQueue<Datagram> dQueue){
        super(dQueue);
        readFromXML(ele);
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
        BigDecimal[] bds = MathUtils.toBigDecimals(data,delimiter); // Split the data and convert to bigdecimals

        if( bds == null ){
            badDataCount++;
            Logger.error("No valid numbers in the data: "+data+" after split on "+delimiter+ " "+badDataCount+"/"+MAX_BAD_COUNT);
            if( badDataCount>=MAX_BAD_COUNT) {
                Logger.error("Too many bad data received, no longer accepting data");
                return false;
            }else{
                return true;
            }
        }else if( badDataCount>0){
            badDataCount--;
        }

        ops.forEach( op -> op.solve(bds) ); // Solve the operations with the converted data

        StringJoiner join = new StringJoiner(delimiter); // prepare a joiner to rejoin the data
        for( int a=0;a<bds.length;a++){
            join.add( bds[a]!=null?bds[a].toPlainString():split[a]); // if no valid bd is found, use the original data
        }

        if( debug ){ // extra info given if debug is active
            Logger.info(getID()+" -> Before: "+data); // how the data looked before
            Logger.info(getID()+" -> After:  "+join); // after applying the operations
        }
        targets.removeIf( t-> !t.writeLine(join.toString()) ); // Send this data to the targets, remove those that refuse it

        if( !label.isEmpty() ){ // If the object has a label associated
            var d = new Datagram(this,label,join.toString()); // build a datagram with it
            d.setOriginID("math:"+id);
            dQueue.add( d ); // add it to the queue
        }
        // If there are no target, no label and no ops that build a command, this no longer needs to be a target
        if( targets.isEmpty() && label.isEmpty() && !doCmd){
            valid=false;
            if( deleteNoTargets )
                dQueue.add( new Datagram("maths:remove,"+id,1,"system") );
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

        fab.comment("Operations go here, possible types: complex (default) ,scale");
        rulesString.forEach( rule -> {
                        fab.addChild("op",rule[2]).attr("index",rule[1]);
                        if( !rule[0].equalsIgnoreCase("complex"))
                            fab.attr("type",rule[0]);
        } );

        return fab.build()!=null;
    }

    /**
     * Read the settings for a mathforward from the given element
     * @param math The math child element
     * @return True if this was successful
     */
    @Override
    public boolean readFromXML(Element math) {

        if( math==null)
            return false;

        id = XMLtools.getStringAttribute( math, "id", "");
        setDelimiter(XMLtools.getStringAttribute( math, "delimiter", delimiter));
        label = XMLtools.getStringAttribute( math, "label", "");

        if( !label.isEmpty() ){ // this counts as a target, so enable it
            valid=true;
        }
        if( id.isEmpty() )
            return false;

        ops.clear();
        XMLtools.getChildElements(math, "op")
                    .forEach( ops -> addOperation(
                            Integer.parseInt(ops.getAttribute("index")),
                            fromStringToOPTYPE(XMLtools.getStringAttribute(ops,"type","complex")),
                            XMLtools.getStringAttribute(ops,"cmd",""),
                            ops.getTextContent()) );

        sources.clear();
        XMLtools.getChildElements(math, "src").forEach( ele ->addSource(ele.getTextContent()) );
        addSource( XMLtools.getStringAttribute( math, "src", ""));
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
    public boolean addOperation(int index, OP_TYPE type, String cmd ,String expression  ){
        if( index <0 ) {
            Logger.error(id + " -> Bad index given " + index);
            return false;
        }

        Operation op;
        switch( type ){
              case COMPLEX:
                    op = new Operation( expression, new MathFab(expression.replace(",",".")),index);
                    break;
            case SCALE: // round a number half up with the amount of digits specified
                    op = new Operation( expression, MathUtils.decodeBigDecimals("i"+index,expression,"scale",0),index);
                    break;
            default:
                return false;
        }
        op.cmd = cmd;
        ops.add(op);

        if( !cmd.isEmpty() ) {// this counts as a target, so enable it
            valid = true;
            doCmd = true;
        }

        rulesString.add(new String[]{type.toString().toLowerCase(),""+index,expression});
        if( xmlOk )
            writeToXML( XMLfab.withRoot(xml, "das"));
        return true;
    }

    /**
     * Convert a string version of OP_TYPE to the enum
     * @return The resulting enum value
     */
    private OP_TYPE fromStringToOPTYPE(String optype) {
        switch(optype){
            case "complex": return OP_TYPE.COMPLEX;
            case "scale": return OP_TYPE.SCALE;
        }
        Logger.error("Invalid op type given, valid ones complex,scale");
        return null;
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
        Function<BigDecimal[],BigDecimal> op; // for the scale type
        MathFab fab;    // for the complex type
        int index;      // index for the result
        String ori="";  // The expression before it was decoded mainly for listing purposes
        String cmd =""; // Command in which to replace the $ with the result

        public Operation(String ori, Function<BigDecimal[],BigDecimal> op, int index ){
            this.op=op;
            this.index=index;
            this.ori=ori;
        }
        public Operation(String ori, MathFab fab, int index ){
            this.fab=fab;
            this.index=index;
            this.ori=ori;
        }
        public BigDecimal solve( BigDecimal[] data){
            BigDecimal bd;

            if( op != null ){
                if( data.length>index) {
                    bd = op.apply(data);
                }else{
                    Logger.error("Tried to do an op with to few elements in the array (data="+data.length+" vs index="+index);
                    return null;
                }
            }else{
                fab.setDebug(debug);
                bd = fab.solve(data,scratchpad);
            }
            if( bd == null ){
                return null;
            }
            if( index!= -1 && index < data.length)
                data[index]=bd;

            if( !cmd.isEmpty()){
                dQueue.add(new Datagram(cmd.replace("$",bd.toString()),1,"system"));
            }
            return bd;
        }
    }
}
