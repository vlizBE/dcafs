package com.hardware.i2c;

import util.tools.Tools;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Storage class for a command.
 */
public class I2CCommand{
    
    enum CMD_TYPE {READ,WRITE,ALTER_OR,ALTER_AND,ALTER_XOR,ALTER_NOT, WAIT_ACK}

    private final ArrayList<CommandStep> steps = new ArrayList<>(); // The various steps in the command

    private int totalReturn=0; // How many bytes are expected to be returned in total
    private int totalIntReturn=0;
    String info=""; // Descriptive text about this command
    int bits = 8;   // How many bits are in the returned data fe. 16 means combine two bytes etc

    private boolean runOnce=false; // Flag that removes this command if it has run

    public I2CCommand(){}

    public I2CCommand(boolean runOnce){
        this.runOnce=runOnce;
    }

    public void setInfo( String info ){
        this.info=info;
    }
    public String getInfo(){
        return info;
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
    /**
     * Adds a write step to this command but these are ints because you can't use bytes in this fashion
     * 
     * @param write The individual bytes tot write, will be given as a array to the method
     * @return this command
     */
    public I2CCommand addWrites( int... write  ){
        if( write == null )
            return null;

        // Convert to a byte array    
        byte[] d = new byte[write.length];
        for( int a=0;a<write.length;a++)
            d[a]=(byte)write[a];  

        steps.add(new CommandStep(d, 0, CMD_TYPE.WRITE));
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
    public I2CCommand setReadBits( int bits ){
        this.bits=bits;
        return this;
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
     * Mark this command for deletion after it has run once.
     */
    public void runOnce(){
        this.runOnce=true;
    }
    /**
     * Check is the runOnce flag is set
     * @return True if this command should be deleted
     */
    public boolean removedAfterUse(){
        return this.runOnce;
    }
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
                default:
                    break;
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

        public CommandStep( byte[] write, int readCount, CMD_TYPE type){
            this.type=type;
            this.write=write;
            this.readCount=readCount;
        }
        public void setBits( int bits ){this.bits=bits;}
        public void setSigned(boolean signed){ this.signed=signed;}
        public void setMsbFirst( boolean msb ){ this.msbFirst=msb;}
        public boolean isSigned(){ return signed;}
        public boolean isMsbFirst(){ return msbFirst;}
    }
}