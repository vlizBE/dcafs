package util.math;

import org.apache.commons.lang3.math.NumberUtils;
import org.tinylog.Logger;
import util.tools.Tools;

import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.function.Function;

public class MathUtils {
    final static int DIV_SCALE = 8;

    /**
     * Converts a simple operation (only two operands) on elements in an array to a function
     * @param first The first element of the operation
     * @param second The second element of the operation
     * @param op The operator to apply
     * @param offset The offset for the index in the array
     * @return The result of the calculation
     */
    public static Function<BigDecimal[],BigDecimal> decodeBigDecimalsOp(String first, String second, String op, int offset ){

        final BigDecimal bd1;
        final int i1;
        final BigDecimal bd2 ;
        final int i2;

        BigDecimal result;
        try{
            if(NumberUtils.isCreatable(first) ) {
                bd1 = NumberUtils.createBigDecimal(first);
                i1=-1;
            }else{
                bd1=null;
                int index = NumberUtils.createInteger( first.substring(1));
                i1 = first.startsWith("o")?index:index+offset;
            }
            if(NumberUtils.isCreatable(second) ) {
                bd2 = NumberUtils.createBigDecimal(second);
                i2=-1;
            }else{
                bd2=null;
                int index = NumberUtils.createInteger( second.substring(1));
                i2 = second.startsWith("o")?index:index+offset;
            }
        }catch( NumberFormatException e){
            Logger.error("Something went wrong decoding: "+first+" or "+second);
            return null;
        }

        Function<BigDecimal[],BigDecimal> proc=null;
        switch( op ){
            case "+":
                try {
                    if (bd1 != null && bd2 != null) { // meaning both numbers
                        proc = x -> bd1.add(bd2);
                    } else if (bd1 == null && bd2 != null) { // meaning first is an index and second a number
                        proc = x -> x[i1].add(bd2);
                    } else if (bd1 != null && bd2 == null) { // meaning first is a number and second an index
                        proc = x -> bd1.add(x[i2]);
                    } else { // meaning both indexes
                        proc = x -> x[i1].add(x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "-":
                try{
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.subtract(bd2);
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
                        proc = x -> x[i1].subtract(bd2);
                    }else if( bd1!=null && bd2==null ){ // meaning first is a number and second an index
                        proc = x -> bd1.subtract(x[i2]);
                    }else{ // meaning both indexes
                        proc = x -> x[i1].subtract(x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "*":
                try{
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.multiply(bd2);
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
                        proc = x -> x[i1].multiply(bd2);
                    }else if( bd1!=null && bd2==null ){ // meaning first is a number and second an index
                        proc = x -> bd1.multiply(x[i2]);
                    }else{ // meaning both indexes
                        proc = x -> x[i1].multiply(x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;

            case "/": // i0/25
                try {
                    if (bd1 != null && bd2 != null) { // meaning both numbers
                        proc = x -> bd1.divide(bd2, DIV_SCALE, RoundingMode.HALF_UP);
                    } else if (bd1 == null && bd2 != null) { // meaning first is an index and second a number
                        proc = x -> x[i1].divide(bd2, DIV_SCALE, RoundingMode.HALF_UP);
                    } else if (bd1 != null && bd2 == null) { //  meaning first is a number and second an index
                        proc = x -> bd1.divide(x[i2], DIV_SCALE, RoundingMode.HALF_UP);
                    } else { // meaning both indexes
                        proc = x -> x[i1].divide(x[i2], DIV_SCALE, RoundingMode.HALF_UP);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;

            case "%": // i0%25
                try{
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.remainder(bd2);
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
                        proc = x -> x[i1].remainder(bd2);
                    }else if( bd1!=null && bd2==null ){ //  meaning first is a number and second an index
                        proc = x -> bd1.remainder(x[i2]);
                    }else{ // meaning both indexes
                        proc = x -> x[i1].remainder(x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "^": // i0/25
                try{
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.pow(bd2.intValue());
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
                        if( bd2.compareTo(BigDecimal.valueOf(0.5)) == 0){ // root
                            proc = x -> x[i1].sqrt(MathContext.DECIMAL64);
                        }else{
                            proc = x -> x[i1].pow(bd2.intValue());
                        }

                    }else if( bd1!=null && bd2==null ){ //  meaning first is a number and second an index
                        proc = x -> bd1.pow(x[i2].intValue());
                    }else{ // meaning both indexes
                        proc = x -> x[i1].pow(x[i2].intValue());
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "scale": // i0/25
                try{
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        proc = x -> bd1.setScale(bd2.intValue(),RoundingMode.HALF_UP);
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
                        proc = x -> x[i1].setScale(bd2.intValue(),RoundingMode.HALF_UP);
                    }else if( bd1!=null && bd2==null ){ //  meaning first is a number and second an index
                        proc = x -> bd1.setScale(x[i2].intValue(),RoundingMode.HALF_UP);
                    }else{ // meaning both indexes
                        proc = x -> x[i1].setScale(x[i2].intValue(),RoundingMode.HALF_UP);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "ln":
                try{
                    if( bd1!=null && bd2!=null ){ // meaning both numbers
                        Logger.error("Todo - ln bd,bd");
                    }else if( bd1==null && bd2!=null){ // meaning first is an index and second a number
                        Logger.error("Todo - ln ix,bd");
                    }else if( bd1!=null && bd2==null ){ //  meaning first is a number and second an index
                        proc = x -> BigDecimal.valueOf(Math.log(x[i2].doubleValue()));
                    }else{ // meaning both indexes
                        proc = x -> BigDecimal.valueOf(Math.log(x[i2].doubleValue()));
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            default:Logger.error("Unknown operand: "+op); break;
        }
        return proc;
    }
    /**
     * Converts a simple operation (only two operands) on elements in an array to a function
     * @param first The first element of the operation
     * @param second The second element of the operation
     * @param op The operator to apply
     * @param offset The offset for the index in the array
     * @return The function resulting from the above parameters
     */
    public static Function<Double[],Double> decodeDoublesOp(String first, String second, String op, int offset ){

        final Double db1;
        final int i1;
        final Double db2 ;
        final int i2;

        try{
            if(NumberUtils.isCreatable(first) ) {
                db1 = NumberUtils.createDouble(first);
                i1=-1;
            }else{
                db1=null;
                int index = NumberUtils.createInteger( first.substring(1));
                i1 = first.startsWith("o")?index:index+offset;
            }
            if(NumberUtils.isCreatable(second) ) {
                db2 = NumberUtils.createDouble(second);
                i2=-1;
            }else{
                db2=null;
                int index = NumberUtils.createInteger( second.substring(1));
                i2 = second.startsWith("o")?index:index+offset;
            }
        }catch( NumberFormatException e){
            Logger.error("Something went wrong decoding: "+first+" or "+second);
            return null;
        }

        Function<Double[],Double> proc=null;
        switch( op ){
            case "+":
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> db1+db2;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> x[i1]+db2;
                    } else if (db1 != null && db2 == null) { // meaning first is a number and second an index
                        proc = x -> db1+x[i2];
                    } else { // meaning both indexes
                        proc = x -> x[i1]+x[i2];
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "-":
                try{
                    if( db1!=null && db2!=null ){ // meaning both numbers
                        proc = x -> db1-db2;
                    }else if( db1==null && db2!=null){ // meaning first is an index and second a number
                        proc = x -> x[i1]-db2;
                    }else if( db1!=null && db2==null ){ // meaning first is a number and second an index
                        proc = x -> db1-x[i2];
                    }else{ // meaning both indexes
                        proc = x -> x[i1]-x[i2];
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "*":
                try{
                    if( db1!=null && db2!=null ){ // meaning both numbers
                        proc = x -> db1*db2;
                    }else if( db1==null && db2!=null){ // meaning first is an index and second a number
                        proc = x -> x[i1]*db2;
                    }else if( db1!=null && db2==null ){ // meaning first is a number and second an index
                        proc = x -> db1*x[i2];
                    }else{ // meaning both indexes
                        proc = x -> x[i1]*x[i2];
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;

            case "/": // i0/25
                try {
                    if (db1 != null && db2 != null) { // meaning both numbers
                        proc = x -> db1/db2;
                    } else if (db1 == null && db2 != null) { // meaning first is an index and second a number
                        proc = x -> x[i1]/db2;
                    } else if (db1 != null && db2 == null) { //  meaning first is a number and second an index
                        proc = x -> db1/x[i2];
                    } else { // meaning both indexes
                        proc = x ->x[i1]/x[i2];
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;

            case "%": // i0%25
                try{
                    if( db1!=null && db2!=null ){ // meaning both numbers
                        proc = x -> db1%db2;
                    }else if( db1==null && db2!=null){ // meaning first is an index and second a number
                        proc = x -> x[i1]%db2;
                    }else if( db1!=null && db2==null ){ //  meaning first is a number and second an index
                        proc = x -> db1%x[i2];
                    }else{ // meaning both indexes
                        proc = x -> x[i1]%x[i2];
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "^": // i0^2
                try{
                    if( db1!=null && db2!=null ){ // meaning both numbers
                        proc = x -> Math.pow(db1,db2);
                    }else if( db1==null && db2!=null){ // meaning first is an index and second a number
                        if( db2.compareTo(0.5) == 0){ // root
                            proc = x -> Math.sqrt(x[i1]);
                        }else{
                            proc = x -> Math.pow(x[i1],db2);
                        }

                    }else if( db1!=null && db2==null ){ //  meaning first is a number and second an index
                        proc = x -> Math.pow(db1,x[i2]);
                    }else{ // meaning both indexes
                        proc = x -> Math.pow(x[i1],x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "scale": // i0/25
                try{
                    if( db1!=null && db2!=null ){ // meaning both numbers
                        proc = x -> Tools.roundDouble(db1,db2.intValue());
                    }else if( db1==null && db2!=null){ // meaning first is an index and second a number
                        proc = x -> Tools.roundDouble(x[i1],db2.intValue());
                    }else if( db1!=null && db2==null ){ //  meaning first is a number and second an index
                        proc = x -> Tools.roundDouble(db1,x[i2].intValue());
                    }else{ // meaning both indexes
                        proc = x -> Tools.roundDouble(x[i1],x[i2].intValue());
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            case "ln":
                try{
                    if( db1!=null && db2!=null ){ // meaning both numbers
                        Logger.error("Todo - ln bd,bd");
                        proc = x -> Math.log(db2);
                    }else if( db1==null && db2!=null){ // meaning first is an index and second a number
                        proc = x -> Math.log(db2);
                    }else if( db1!=null && db2==null ){ //  meaning first is a number and second an index
                        proc = x -> Math.log(x[i2]);
                    }else{ // meaning both indexes
                        proc = x -> Math.log(x[i2]);
                    }
                }catch (IndexOutOfBoundsException | NullPointerException e){
                    Logger.error("Bad things when "+first+" "+op+" "+second+ " was processed");
                    Logger.error(e);
                }
                break;
            default:Logger.error("Unknown operand: "+op); break;
        }
        return proc;
    }

    /**
     * Convert a delimited string to BigDecimals arra where possible, fills in null if not
     * @param list The delimited string
     * @param delimiter The delimiter to use
     * @return The resulting array
     */
    public static BigDecimal[] toBigDecimals(String list, String delimiter ){
        String[] split = list.split(delimiter);
        var bds = new BigDecimal[split.length];
        int nulls=0;

        for( int a=0;a<split.length;a++){
            if( NumberUtils.isCreatable(split[a])) {
                try {
                    bds[a] = NumberUtils.createBigDecimal(split[a]);
                }catch(NumberFormatException e) {
                    bds[a] = new BigDecimal( NumberUtils.createBigInteger(split[a]));
                }
            }else{
                bds[a] = null;
                nulls++;
            }
        }
        return nulls==bds.length?null:bds;
    }

    /**
     * Convert a delimited string to an array of doubles, inserting null where conversion is not possible
     * @param list The delimited string
     * @param delimiter The delimiter to use
     * @return The resulting array
     */
    public static Double[] toDoubles(String list, String delimiter ){
        String[] split = list.split(delimiter);
        var dbs = new Double[split.length];
        int nulls=0;

        for( int a=0;a<split.length;a++){
            if( NumberUtils.isCreatable(split[a])) {
                try {
                    dbs[a] = NumberUtils.createDouble(split[a]);
                }catch(NumberFormatException e) {
                    // hex doesn't go wel to double...
                    dbs[a] = NumberUtils.createBigInteger(split[a]).doubleValue();
                }
            }else{
                dbs[a] = null;
                nulls++;
            }
        }
        return nulls==dbs.length?null:dbs;
    }
    /**
     * Convert a 12bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned8bit( int ori ){
        if( ori>0x80 ){ //two's complement
            ori = -1*((ori^0xFF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 12bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned10bit( int ori ){
        if( ori>0x200 ){ //two's complement
            ori = -1*((ori^0x3FF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 12bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned12bit( int ori ){
        if( ori>0x800 ){ //two's complement
            ori = -1*((ori^0xFFF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 16bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned16bit( int ori ){
        if( ori>0x8000 ){ //two's complement
            ori = -1*((ori^0xFFFF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 20bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned20bit( int ori ){
        if( ori>0x80000 ){ //two's complement
            ori = -1*((ori^0xFFFFF) + 1);
        }
        return ori;
    }

    /**
     * Convert a 24bit 2's complement value to an actual signed int
     * @param ori The value to convert
     * @return The signed int
     */
    public static int toSigned24bit( int ori ){
        if( ori>0x800000 ){ //two's complement
            ori = -1*((ori^0xFFFFFF) + 1);
        }
        return ori;
    }

    /**
     * Method that does a crc checksum for the given nmea string
     *
     * @param nmea The string to do the checksum on
     * @return True if checksum is ok
     */
    public static boolean doNMEAChecksum(String nmea) {
        int checksum = 0;
        for (int i = 1; i < nmea.length() - 3; i++) {
            checksum = checksum ^ nmea.charAt(i);
        }
        return nmea.endsWith(Integer.toHexString(checksum).toUpperCase());
    }

    public static String getNMEAchecksum(String nmea) {
        int checksum = 0;
        for (int i = 1; i < nmea.length(); i++) {
            checksum = checksum ^ nmea.charAt(i);
        }
        String hex = Integer.toHexString(checksum).toUpperCase();
        if (hex.length() == 1)
            hex = "0" + hex;
        return hex;
    }

    /**
     * Calculate the MD5 checksum of a file
     *
     * @param file The path to the file
     * @return The calculated checksum in ascii
     */
    public static String calculateMD5(Path file) {
        MessageDigest md;
        Logger.info("Calculating MD5 for " + file.toString());

        try {
            md = MessageDigest.getInstance("MD5");
            md.update(Files.readAllBytes(file));
            byte[] digest = md.digest();
            return DatatypeConverter.printHexBinary(digest);
        } catch (NoSuchAlgorithmException | IOException e) {
            Logger.error(e);
            return "";
        }
    }

    /**
     * Calculate the CRC16 according to the modbus spec
     *
     * @param data The data to calculate this on
     * @param append If true the crc will be appended to the data, if not only crc will be returned
     * @return Result based on append
     */
    public static byte[] calcCRC16_modbus(byte[] data, boolean append) {
        byte[] crc = calculateCRC16(data, data.length, 0xFFFF, 0xA001);
        if (!append)
            return crc;

        return ByteBuffer.allocate(data.length+1)
                .put( data )
                .put(crc,0,1)
                .array();
    }

    /**
     * Calculate CRC16 of byte data
     * @param data The data to calculate on
     * @param cnt Amount of data to process
     * @param start The value to start from
     * @param polynomial Which polynomial to use
     * @return An array containing remainder and dividend
     */
    public static byte[] calculateCRC16(byte[] data, int cnt, int start, int polynomial) {

        for (int pos = 0; pos < cnt; pos++) {
            start ^= (data[pos] & 0xFF);
            for (int x = 0; x < 8; x++) {
                boolean wasOne = start % 2 == 1;
                start >>>= 1;
                if (wasOne) {
                    start ^= polynomial;
                }
            }
        }
        return new byte[]{ (byte) Integer.remainderUnsigned(start, 256), (byte) Integer.divideUnsigned(start, 256) };
    }

    /**
     * Calculate the standard deviation
     *
     * @param set      The set to calculate the stdev from
     * @param decimals The amount of decimals
     * @return Calculated Standard Deviation
     */
    public static double calcStandardDeviation(ArrayList<Double> set, int decimals) {
        double sum = 0, sum2 = 0;
        int offset = 0;
        int size = set.size();

        if( size == 0 )
            return 0;

        for (int a = 0; a < set.size(); a++) {
            double x = set.get(a);
            if (Double.isNaN(x) || Double.isInfinite(x)) {
                set.remove(a);
                a--;
            }
        }

        if (size != set.size()) {
            Logger.error("Numbers in set are NaN or infinite, lef " + set.size()
                    + " of " + size + " elements");
        }
        for (double d : set) {
            sum += d;
        }

        double mean = sum / (set.size() - offset);
        for (double d : set) {
            sum2 += (d - mean) * (d - mean);
        }
        return Tools.roundDouble(Math.sqrt(sum2 / set.size()), decimals);
    }

    /**
     * Performes a second order A*x²2 + B*x + C computation in which A, B and C are
     * calibration coefficients
     *
     * @param cal      The calibration coëfficients
     * @param hex      The x in the formula in hexadecimal representation
     * @param decimals How many decimals in the result
     * @return Calculated value
     */
    public static double calc2ndOrder(BigDecimal[] cal, String hex, int decimals) {
        return calc2ndOrder( cal, Long.decode(hex), decimals);
    }

    /**
     * Performes a second order A*x²2 + B*x + C computation in which A, B and C are
     * calibration coefficients
     *
     * @param cal      The calibration coëfficients
     * @param dec      The x in the formula in decimal form
     * @param decimals How many decimals in the result
     * @return Calculated value
     */
    public static double calc2ndOrder(BigDecimal[] cal, double dec, int decimals) {
        if (cal == null) {
            Logger.error("Bad Calibrations values given");
            return -999;
        }
        try {
            BigDecimal bd = BigDecimal.valueOf(dec);
            BigDecimal bd2 = bd.pow(2);
            BigDecimal x2 = bd2.multiply(cal[0]);
            BigDecimal x1 = bd.multiply(cal[1]);
            BigDecimal result = x2.add(x1).add(cal[2]);
            return Tools.roundDouble(result.doubleValue(), decimals);
        } catch (NumberFormatException e) {
            Logger.error("Bad value received for 2nd order conversion: " + dec);
            return -999;
        }
    }

    /**
     * Performes a third order A*x³ + B*x² + C*x + D computation in which A, B, C and D are
     * calibration coefficients
     *
     * @param cal      The calibration coëfficients
     * @param dec      The x in the formula in decimal form
     * @param decimals How many decimals in the result
     * @return Calculated value
     */
    public static double calc3rdOrder(BigDecimal[] cal, double dec, int decimals) {
        if (cal == null) {
            Logger.error("Bad Calibrations values given");
            return -999;
        }
        try {
            BigDecimal bd = BigDecimal.valueOf(dec);
            BigDecimal bd2 = bd.pow(2);
            BigDecimal bd3 = bd.pow(3);

            BigDecimal x3 = bd3.multiply(cal[0]);
            BigDecimal x2 = bd2.multiply(cal[1]);
            BigDecimal x1 = bd.multiply(cal[2]);
            BigDecimal result = x3.add(x2).add(x1).add(cal[3]);
            return Tools.roundDouble(result.doubleValue(), decimals);
        } catch (NumberFormatException e) {
            Logger.error("Bad value received for 2nd order conversion: " + dec);
            return -999;
        }
    }

    /**
     * Convert the BCB representation of a number to the actual number
     * @param bcd The Binary coded digital value
     * @return A standard integer
     */
    public static int bcdToNumeric( int bcd ){
        int ten=bcd/16;
        int one=bcd%16;
        return ten*10+one;
    }

    /**
     * Convert the number to BCB representation eg. 25 to 0x25
     * @param number The number to convert
     * @return An integer BCD formatted
     */
    public static int numericToBCD( int number ){
        return (number/10)*16+number%10;
    }
}
