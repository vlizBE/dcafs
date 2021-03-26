package com.hardware.spi;

import java.util.ArrayList;
import java.util.Arrays;

import com.diozero.api.SpiClockMode;
import com.diozero.api.SpiDevice;

import org.apache.commons.lang3.tuple.Pair;
import org.tinylog.Logger;
import org.w3c.dom.Element;

import util.tools.Tools;
import util.xml.XMLtools;

public class ExtSpiDevice extends SpiDevice{

    public static final byte NOP = (byte)0xFF;

    public ExtSpiDevice( int bus, int cs, int frequency, SpiClockMode clock, boolean dunno){
        super( bus,cs,frequency,clock,dunno);
    }
    /*************************************************************************************************/
    /****************************** S I N G L E B Y T E  R E G I S T E R *****************************/
    /*************************************************************************************************/
    /**
     * Read a single byte register once or continuously
     * @param register THe register to read
     * @param singleOrCont The byte that determines if it's once or continuously
     * @return The read byte
     */
    public int readSingleByteRegister( byte register, byte singleOrCont ){
        byte[] b = { (byte) (register | singleOrCont),  NOP };
        byte[] c = this.writeAndRead(b);
        return Tools.toUnsigned(c[1]);
    }
    /**
     * Write to a single byte register
     * @param register The register to write to
     * @param value The value to write to it
     */
    public void writeSingleByteRegister( byte register, byte value ){
        byte[] b = {register, value };
        this.write(b);
    }
    /*************************************************************************************************/
    /********************************* T W O  B Y T E  R E G I S T E R *******************************/
    /*************************************************************************************************/
    /**
     * Write new values to a word register (two byte register)
     * @param register The register address
     * @param msb The value for the MSByte
     * @param lsb The value for the LSByte
     */
    public void writeWordRegister( byte register, byte msb, byte lsb ){
        byte[] b = {register, msb, lsb };
        this.write(b);
    }
    /**
     * Read the values from a word register (two byte register)
     * @param register The register address
     * @param readMod Modification to the register to signify reading
     * @return An integer consisting of the two read bytes
     */
    public int readWordRegister( byte register, byte readMod ){
        byte[] b = { (byte) (register | readMod), NOP, NOP };
        byte[] c = this.writeAndRead(b);
        return Tools.toUnsigned(c[1])*256+Tools.toUnsigned(c[2]);
    }

    public void alterFirstOfWordRegister( byte register, byte readMod, byte value ){
        byte[] b = { (byte) (register | readMod), NOP, NOP };
        byte[] c = this.writeAndRead(b);
        b[0]=register;
        b[1]=value;
        b[2]=c[2];
        this.write(b);
    }
    
    public void alterSecondOfWordRegister( byte register, byte readMod, byte value ){
        byte[] b = { (byte) (register | readMod), NOP, NOP };
        byte[] c = this.writeAndRead(b);
        b[0]=register;
        b[1]=c[1];
        b[2]=value;
        this.write(b);
    }

    /* **************************** T H R E E  B Y T E  R E G I S T E R *******************************/
    /**
     * Read a triple byte register
     * 
     * @param register The register to read
     * @param singleOrCont The byte that determines if it's once or continuously (so next time nor write is needed)
     * @return The register value
     */
    public int readTripleByteRegister( byte register, byte singleOrCont ){
        byte[] b = { (byte) (register | singleOrCont) , NOP, NOP, NOP };
        byte[] c = this.writeAndRead(b);
        return Tools.toUnsigned(c[1])*65536+Tools.toUnsigned(c[2])*256+Tools.toUnsigned(c[3]);
    }


    static class SPICommand {
        ArrayList<Pair<byte[],Integer>> cmds = new ArrayList<>();
        byte nop=Byte.MIN_VALUE;

        public void readCommand( Element cmd ){
            String regS = XMLtools.getStringAttribute( cmd , "reg", "" );
            int read = XMLtools.getIntAttribute( cmd , "return", 0 );

            byte regB=0x00;
            byte[] regSplit;
            if( !regS.isBlank() ){
                boolean or = regS.contains("|");
                boolean and = regS.contains("&");
                regS = regS.replace("\\|", " ").replace("\\&", "");
                regSplit = Tools.fromHexStringToBytes(regS);
                if( or || and ){
                    regB = (byte) (or?(regSplit[0]|regSplit[1]):(regSplit[0]&regSplit[1]));
                }else{
                    regB = regSplit[0];
                }                
            }

            switch( cmd.getNodeName() ){
                case "read": 
                    byte[] write = new byte[read];
                    Arrays.fill(write,nop);
                    write[0] = regB;
                    cmds.add( Pair.of( write,2) );
                    break;
                case "write": 
                    cmds.add( Pair.of( Tools.fromHexStringToBytes( cmd.getTextContent() ),0) );
                    break;
                case "alter": 
                    break;
                default:
                    Logger.error("Unknown nodename: "+cmd.getNodeName());
                    break;    
            }
        }
    }
}