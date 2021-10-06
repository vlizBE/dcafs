package io.hardware.i2c;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.math.MathFab;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLtools;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/**
 * Storage class for a command.
 */
public class I2CCommand{
    
    enum CMD_TYPE {READ,WRITE,ALTER_OR,ALTER_AND,ALTER_XOR,ALTER_NOT, WAIT_ACK,MATH,WAIT,DISCARD,REPEAT,RETURN}
    enum OUTPUT_TYPE{DEC,HEX,BIN,CHAR}

    private final ArrayList<CommandStep> steps = new ArrayList<>(); // The various steps in the command

    private int totalReturn=0; // How many bytes are expected to be returned in total
    private int totalIntReturn=0;
    String info=""; // Descriptive text about this command
    int bits = 8;   // How many bits are in the returned data fe. 16 means combine two bytes etc
    int lastRepeat=-1;
    private boolean runOnce=false; // Flag that removes this command if it has run
    private boolean msbFirst=true;
    private OUTPUT_TYPE outType=OUTPUT_TYPE.DEC;

    public I2CCommand(){}

    public void setInfo( String info ){
        this.info=info;
    }
    public void setMsbFirst( boolean msb){
        this.msbFirst=msb;
    }
    public I2CCommand setReadBits( int bits ){
        this.bits=bits;
        return this;
    }
    public String getInfo(){
        return info;
    }
    public void setOutType( String type ){
        switch(type.toLowerCase()){
            case "dec": outType=OUTPUT_TYPE.DEC; break;
            case "hex": outType=OUTPUT_TYPE.HEX; break;
            case "bin": outType=OUTPUT_TYPE.BIN; break;
            case "char": outType=OUTPUT_TYPE.CHAR; break;
        }
    }
    public OUTPUT_TYPE getOutType(){
        return outType;
    }
    public boolean addStep( Element ele ){

        String reg = XMLtools.getStringAttribute( ele , "reg", "" );

        boolean ok=false;
        switch( ele.getNodeName() ){
            case "read":
                boolean msb = XMLtools.getBooleanAttribute( ele, "msbfirst",msbFirst);
                boolean signed = XMLtools.getBooleanAttribute(ele, "signed",false);
                // Allow to use ix to refer to received values
                String retu = XMLtools.getStringAttribute( ele , "return", "0" ).replace("i","-");
                if( retu.equalsIgnoreCase("0")) {
                    Logger.warn("No use reading 0 bytes");
                    break;
                }
                ok = addRead( Tools.fromHexStringToBytes( reg ) // register address
                        , NumberUtils.toInt(retu) // how many bytes
                        , XMLtools.getIntAttribute(ele,"bits",bits) //how many bits to combine
                        , XMLtools.getBooleanAttribute( ele, "msbfirst",msb)
                        , signed) != null;
                break;
            case "write":
                if( ele.getTextContent().isEmpty() && !reg.isEmpty()){
                    ok = addWrite( Tools.fromHexStringToBytes( reg ) ) != null;
                }else if( reg.isEmpty() && !ele.getTextContent().isEmpty() ){
                    ok = addWrite( Tools.fromHexStringToBytes( ele.getTextContent() )) != null;
                }else{
                    ok = addWrite( ArrayUtils.addAll( Tools.fromHexStringToBytes( reg ),
                            Tools.fromHexStringToBytes( ele.getTextContent() )) ) != null;
                }
                break;
            case "alter":
                byte[] d = ArrayUtils.addAll( Tools.fromHexStringToBytes( reg ),
                        Tools.fromHexStringToBytes( ele.getTextContent() ));
                ok = addAlter( d, XMLtools.getStringAttribute( ele , "operand", "or" ) ) != null;
                break;
            case "wait_ack":
                if( !ele.getTextContent().isEmpty() ){
                    int cnt = Tools.parseInt(ele.getTextContent(), -1);
                    if( cnt != -1){
                        ok = addWaitAck(cnt) != null;
                    }
                }
                break;
            case "math":
                if( !ele.getTextContent().isEmpty() ){
                    addMath(ele.getTextContent());
                    ok=true;
                }
                break;
            case "wait":
                if( !ele.getTextContent().isEmpty() ) {
                    steps.add( new CommandStep((int)TimeTools.parsePeriodStringToMillis(ele.getTextContent()), CMD_TYPE.WAIT));
                    ok = true;
                }
                break;
            case "repeat":
                Logger.info("Repeat '"+ele.getAttribute("cnt"));
                if( ele.hasAttribute("cnt") ) {
                    steps.add( new CommandStep( NumberUtils.toInt(ele.getAttribute("cnt") ), CMD_TYPE.REPEAT));
                    lastRepeat=steps.size();
                    ok = true;
                }
                break;
            case "return":
                steps.add( new CommandStep(lastRepeat, CMD_TYPE.RETURN));
                ok = true;
                break;
            case "discard":
                if( !ele.getTextContent().isEmpty() ) {
                    steps.add( new CommandStep(NumberUtils.toInt(ele.getTextContent()), CMD_TYPE.DISCARD));
                    ok = true;
                }
                break;
            default:
                Logger.error("Unknown command: "+ele.getNodeName());
                break;
        }
        if(!ok ){
            Logger.error("Invalid data received for command");
        }
        return ok;

    }
     /* ******************************************************************************* */
     /**
      * Adds a read step to this command
      *
      * @param write The data to write in order to get the replies
      * @param replies The amount of data to get back
      * @return this command
      */
    public I2CCommand addRead( byte[] write, int replies, int bits, boolean msbFirst, boolean signed ){
        if( write==null )
            return null;
        var cs = new CommandStep(write, replies, CMD_TYPE.READ);
        cs.setSigned(signed);
        cs.setBits(bits);
        cs.setMsbFirst(msbFirst);
        steps.add(cs);
        totalReturn += replies;

        int bytes = bits/8;
        bytes += bits%8==0?0:1;
        totalIntReturn += replies/bytes;

        return this;
    }
    /**
     * Adds a write step to this command
     *
     * @param write The data to write in order to get the replies
     * @return this command
     */
    public I2CCommand addWrite( byte[] write ){
       if( write.length==0 )
            return null;

        steps.add(new CommandStep(write, 0, CMD_TYPE.WRITE));
        return this;
    }

    public I2CCommand addWaitAck( int count ){
        byte[] b = {(byte)count};

        steps.add(new CommandStep(b, 1, CMD_TYPE.WAIT_ACK));
        totalReturn += 1;
        return this;
    }
    /**
     * Adds a step to this command that alters a register
     * 
     * @param data First byte the register, second byte the alteration
     * @param op Which operand needs to be applied options: or,and,xor,not
     * @return This command
     */
    public I2CCommand addAlter(  byte[] data, String op ){
        if( data == null || data.length==0 )
            return null;
        CMD_TYPE cmdType;
        switch (op) {
            case "and":
                cmdType = CMD_TYPE.ALTER_AND;
                break;
            case "xor":
                cmdType = CMD_TYPE.ALTER_XOR;
                break;
            case "not":
                cmdType = CMD_TYPE.ALTER_NOT;
                break;
            default:
                cmdType = CMD_TYPE.ALTER_OR;
            break;
        }
        steps.add(new CommandStep(data, 0, cmdType));
        return this;
    }
    public I2CCommand addMath( String op ){
        var ops = op.split("=");
        var fab = MathFab.newFormula(ops[1]);
        if( fab.isValid() ){
            steps.add( new CommandStep( NumberUtils.toInt(ops[0].substring(1)), fab));
            Logger.info("Parsed "+op+" in a fab");
        }else{
            Logger.error("Failed to parse "+op+" to a mathfab");
        }

        return this;
    }
    public void addRepeat( int count ){
        if( count == 0){
            Logger.error("Invalid repeat count");
            return;
        }
        lastRepeat=steps.size();
        steps.add( new CommandStep(count, CMD_TYPE.REPEAT));
    }
    public void addReturn(){
        steps.add( new CommandStep(lastRepeat, CMD_TYPE.RETURN));
    }
    /* ******************************************************************************* */
     /**
      * Get all the commands stored in this object

      * @return A Triple with bytes to send, amount to read, and kind of command it is
      */
    public List<CommandStep> getAll(){
        return steps;
    }
    /**
	 * Get the amount of bytes that are expected to be received after the full command
	 * 
	 * @return Total amount of bytes received during the execution of this command
	 */
	public int getTotalReturn(){
		return totalReturn;	
    }
    public int getTotalIntReturn(){
        return totalIntReturn;
    }
    /* ******************************************************************************* */
    /**
     * Convert this command to a humanly readable format
     */
    public String toString( String prefix ){
        StringJoiner b = new StringJoiner("\r\n"+prefix,prefix,"");
        for( CommandStep cmd : steps ){
            switch( cmd.type ){
                case ALTER_AND:b.add("Altering reg 0x"+Integer.toHexString(cmd.write[0])+" with and of "+Integer.toHexString(cmd.write[1]) );break;
                case ALTER_NOT:b.add("Altering reg 0x"+Integer.toHexString(cmd.write[0])+" with not of "+Integer.toHexString(cmd.write[1]) );break;
                case ALTER_OR: b.add("Altering reg 0x"+Integer.toHexString(cmd.write[0])+" with or of "+Integer.toHexString(cmd.write[1]) );break;
                case ALTER_XOR:b.add("Altering reg 0x"+Integer.toHexString(cmd.write[0])+" with xor of "+Integer.toHexString(cmd.write[1]) ); break;
                case READ:     b.add("Read "+(cmd.readCount==1?"a single byte":cmd.readCount+" bytes")+" from reg "+Tools.fromBytesToHexString(cmd.write) ); break;
                case WRITE:    b.add("Write "+Tools.fromBytesToHexString(cmd.write,1, cmd.write.length)+" to reg 0x"+Integer.toHexString(cmd.write[0]) ); break;
                case WAIT_ACK: b.add("Do "+cmd.write[0]+" attempts at addressing the device."); break;
                case MATH:     b.add("Solve "+cmd.fab.getOri()); break;
                case WAIT:     b.add("Wait for "+TimeTools.convertPeriodtoString(cmd.readCount, TimeUnit.MILLISECONDS));break;
                case DISCARD:  b.add("Discard the content of the result buffer from index "+cmd.readCount); break;
                case REPEAT:   b.add("Repeat the section "+cmd.readCount+" times."); break;
                case RETURN:   b.add("Repeat it up to here"); break;
                default: break;
            }
        }
        return b.toString();
    }
    public static class CommandStep{
        CMD_TYPE type;
        byte[] write;
        int readCount;
        int bits=8;
        boolean signed = false;
        boolean msbFirst = true;
        MathFab fab;
        int index=-1;

        public CommandStep( byte[] write, int readCount, CMD_TYPE type){
            this.type=type;
            this.write=write;
            this.readCount=readCount;
        }
        public CommandStep( int readCount, CMD_TYPE type){
            this.type=type;
            this.readCount=readCount;
        }
        public CommandStep(int index,MathFab fab){
            this.fab=fab;
            type=CMD_TYPE.MATH;
            this.index=index;
        }
        public void setBits( int bits ){this.bits=bits;}
        public void setSigned(boolean signed){ this.signed=signed;}
        public void setMsbFirst( boolean msb ){ this.msbFirst=msb;}

        public boolean isSigned(){ return signed;}
        public boolean isMsbFirst(){ return msbFirst;}
    }
}