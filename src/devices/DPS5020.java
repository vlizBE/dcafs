package devices;

import org.apache.commons.lang3.ArrayUtils;
import util.math.MathUtils;

public class DPS5020 {
    char address=1;

    public DPS5020(){

    }
    public DPS5020( char address){
        this.address=address;
    }
    public static byte[] setVoltage( int address, double voltage ){
        int volts = (int)(voltage*100);
        byte[] data = { (byte) address,0x06,0x00,0x00, (byte)Integer.divideUnsigned(volts,256),(byte)Integer.remainderUnsigned(volts,256) };
        return MathUtils.calcCRC16_modbus(data,true);
    }
    public static byte[] seCurrentLimit( int address, double current ){
        int amps = (int)(current*100);
        byte[] data = { (byte) address,0x06,0x00,0x01, (byte)Integer.divideUnsigned(amps,256),(byte)Integer.remainderUnsigned(amps,256) };
        return MathUtils.calcCRC16_modbus(data,true);
    }
    public static byte[] reqOutputValues( int address ){
        return new byte[]{ (byte) address, 0x03, 0x00, 0x02, 0x00, 0x02, 0x65, (byte)0xCB };
    }
    public static double[] procOutputValues( byte[] data ){
        if( verifyCRC(data) ){
            double volt = (double) data[3]*256+data[4];
            double current = (double) data[5]*256+data[6];           
            return new double[]{volt/100,current/100};
        }else{
            return new double[0];
        }
    }
    public static boolean verifyCRC( byte[] data){
        return verifyCRC( data,data.length);
    }
    public static boolean verifyCRC( byte[] data,int length){
        byte[] crc = MathUtils.calcCRC16_modbus( ArrayUtils.subarray(data,0,length-2), false);
        return crc[0]==data[length-2] && crc[1]==data[length-1];
    }
}