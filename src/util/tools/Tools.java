package util.tools;

import org.tinylog.Logger;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.*;
import java.util.Enumeration;
import java.util.StringJoiner;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * A collection of various often used small methods that do a variety of usefull
 * things
 * 
 * @author Michiel T'Jampens
 */
public class Tools {

    /* ********************************* D O U B L E ********************************************* */
    /**
     * More robust way of parsing strings to double then the standard
     * Double.parseDouble method and return a chosen value on error Removes: space
     * ',' '\n' '\r'
     * 
     * @param number The string double to parse
     * @param error  The double to return if something went wrong
     * @return The parsed double if successful or the chosen error value.
     */
    public static double parseDouble(String number, double error) {

        if (number == null)
            return error;

        number = number.trim().replace(",", ".").replace("\n", "").replace("\r", "");

        if (number.isBlank()) {
            return error;
        }
        try {
            return Double.parseDouble(number);
        } catch (NumberFormatException e) {
            return error;
        }
    }

    /**
     * Rounds a double to a certain amount of digits after the comma
     * 
     * @param r            The double to round
     * @param decimalPlace The amount of digits
     * @return The rounded double
     */
    public static double roundDouble(double r, int decimalPlace) {
        if (Double.isInfinite(r) || Double.isNaN(r) || decimalPlace < 0)
            return r;
        BigDecimal bd = BigDecimal.valueOf(r);
        return bd.setScale(decimalPlace, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Rounds a double to a certain amount of digits after the comma
     * 
     * @param r            The double to round
     * @param decimalPlace The amount of digits
     * @return The rounded double
     */
    public static String fixedLengthDouble(double r, int decimalPlace) {
        if (Double.isInfinite(r) || Double.isNaN(r) || decimalPlace < 0)
            return "-999";
        BigDecimal bd = BigDecimal.valueOf(r);
        double d = bd.setScale(decimalPlace, RoundingMode.HALF_UP).doubleValue();
        StringBuilder l = new StringBuilder("" + d);
        while ((l.length() - l.indexOf(".") - 1) < decimalPlace) {
            l.append("0");
        }
        return l.toString();
    }

    /**
     * Parse an element of an array of strings to a double
     * 
     * @param array The array containing the strings
     * @param index The index of the element
     * @param error The value to return if the parsing fails
     * @return The parsed string
     */
    public static double parseDoubleArray(String[] array, int index, double error) {
        if (array.length > index && index != -1) {
            return parseDouble(array[index], error);
        }
        return error;
    }

    /* ******************************* I N T E G E R  ************************************************************ */
    /**
     * More robust way of parsing strings to integer then the standard
     * Integer.parseInteger method and return a chosen value on error Removes: space
     * ',' '\n' '\r'
     * 
     * @param number The string integer to parse, if starts with 0x, it's considered
     *               hex
     * @param error  The integer to return if something went wrong
     * @return The parsed integer if successful or the chosen error value.
     */
    public static int parseInt(String number, int error) {
        try {
            number = number.trim().replace("\n", "").replace("\r", "").replace(";", "");

            if (number.startsWith("0x")) {
                return Integer.parseInt(number.substring(2), 16);
            }
            return Integer.parseInt(number);
        } catch (NumberFormatException e) {
            return error;
        }
    }
    public static boolean parseBool( String value, boolean error){
        value=value.toLowerCase();
        if( value.equals("yes")||value.equals("true")||value.equals("1"))
            return true;
        if( value.equals("no")||value.equals("false")||value.equals("0"))
            return false;
        return error;
    }
    public static int toUnsigned(byte b) {
        int a = b;
        return a < 0 ? a + 256 : a;
    }

    /**
     * Adds zeros to the front of an integer till it has the specified length
     * @param nr the integer to alter
     * @param length the requested length
     * @return the altered integer as a string
     */
    public static String addLeadingZeros(int nr, int length) {
        StringBuilder res = new StringBuilder("" + nr);
        while (res.length() < length)
            res.insert(0, "0");
        return res.toString();
    }

    /* ************************************** S T R I N G ******************************************************** */

    public static String getEOLString( String eol ){

        if( eol.length()==3 && eol.charAt(0)==127 && eol.charAt(1)==127 && eol.charAt(0)==127 )
            return "nextion";
        return eol.replace("\r","cr")
                    .replace("\n","lf")
                    .replace("\t","tab");

    }
   /**
	 * Convert the descriptive name of the delimiter to the actual findable string
	 * @param delimiter The descriptive name
	 * @return The findable version of the descriptive name
	 */
    public static String getDelimiterString( String delimiter ){
        delimiter = delimiter.replace("cr","\r")
                             .replace("lf","\n")
                             .replace("tab","\t")
                             .replace("nextion","\\x7F\\x7F\\x7F");
        return fromEscapedStringToBytes(delimiter);
	}
    /**
     * Method to remove the values from the text that aren't in the ascii range
     * 
     * @param text The text to check for non-ascii
     * @return The cleaned result
     */
    public static String removeNonAscii(String text) {
        char[] a = text.toCharArray();
        StringBuilder result = new StringBuilder();
        for (int x = 0; x < a.length; x++) {
            if ((int) a[x] < 128) {
                result.append(a[x]);
            } else {
                int start = x > 50 ? (x - 50) : 0;
                int cnt = a.length > (start + 100) ? start + 100 : a.length - start - 1;
                Logger.error("Bad data within: " + String.copyValueOf(a, start, cnt));
            }
        }
        return result.toString();
    }

    /**
     * Remove backspaces from a string
     * 
     * @param data The string to remove backspaces from
     * @return The cleaned data
     */
    public static String cleanBackspace(String data) {
        char[] b = data.toCharArray();

        for (int a = 1; a < b.length; a++) {
            if ((int) b[a] == 8 || (int) b[a] == 127) {
                b[a] = '@';
            }
        }
        String d = new String(b);
        while (d.contains("@")) {
            int x = d.indexOf("@");
            d = d.substring(0, x - 1) + d.substring(x + 1);
        }
        return d;
    }

    /* ************************** * H E X A D E C I M A L ********************************************************* */
    /**
     * Converts a array of characters to a space separated string of hexadecimals
     * (0x00 0x01)
     * 
     * @param data The array to parse
     * @return The hex string
     */
    public static String fromCharToHexString(char[] data) {
        StringJoiner join = new StringJoiner(" ");

        for (char a : data) {
            String hex = Integer.toHexString(a).toUpperCase();
            join.add((hex.length() == 1 ? "0x0" : "0x") + hex);
        }
        return join.toString();
    }

    /**
     * Convert a string to the hexadecimal representation of the characters
     * 
     * @param data The string to convert
     * @return Hexadecimal version of the data
     */
    public static String fromAsciiToHex(String data) {
        return fromCharToHexString(data.toCharArray());
    }

    /**
     * Converts a array of bytes to a space separated string of hexadecimals (0x00
     * 0x01)
     * 
     * @param data The array to parse
     * @return The hex string
     */
    public static String fromBytesToHexString(byte[] data) {
        if (data == null)
            return "";
        return fromBytesToHexString(data, 0, data.length);
    }

    /**
     * Converts a part of an array of characters to a space separated string of
     * hexadecimals (0x00 0x01)
     * 
     * @param data   The array to parse
     * @param offset Start index
     * @param length Amount of bytes from the start to convert
     * @return The hex string
     */
    public static String fromBytesToHexString(byte[] data, int offset, int length) {
        if (data == null)
            return "";

        StringJoiner join = new StringJoiner(" 0x", "0x", "");
        for (int x = offset; x < length && x < data.length; x++) {
            String hex = Integer.toHexString(data[x]).toUpperCase();
            if (hex.length() > 2) {
                hex = hex.substring(hex.length() - 2);
            }
            join.add((hex.length() == 1 ? "0" : "") + hex);
        }
        return join.toString();
    }

    /**
     * Converts a delimited string of hexes to a byte array
     * 
     * @param line The delimited line (will split on space, komma and semicolon)
     * @return The resulting array
     */
    public static byte[] fromHexStringToBytes(String line) {

        line = line.toLowerCase().replace("0x", "");

        byte[] result = Tools.fromBaseToBytes(16, Tools.splitList(line));
        if (result.length == 0) {
            Logger.error("Failed to convert " + line);
        }
        return result;
    }

    /**
     * Converts a delimited string of decimals to a byte array
     * 
     * @param line The delimited line (will split on space, komma and semicolon)
     * @return The resulting array
     */
    public static byte[] fromDecStringToBytes(String line) {
        byte[] result = Tools.fromBaseToBytes(10, Tools.splitList(line));
        if (result.length == 0) {
            Logger.error("Failed to convert " + line);
        }
        return result;
    }
    public static String[] extractNumbers(String txt ){
        return Pattern.compile("[0-9]+\\.?[0-9]*")
                .matcher(txt)
                .results()
                .map(MatchResult::group)
                .toArray(String[]::new);
    }

    /**
     * Replaces all the occurrences of the byte size hex escape sequences (fe.\x10) with their respective value
     * @param txt The text in which to replace them
     * @return The resulting bytes
     */
    public static String fromEscapedStringToBytes( String txt ){

        // Replace the known ones like \t, \r and \n
        txt = txt.replace("\\t","\t")
                    .replace("\\r","\r")
                    .replace("\\n","\n")
                    .replace("\\0","\0");

        // First extract all the hexes
        var hexes = Pattern.compile("[\\\\][x]([0-9]|[A-F]){1,2}")
                .matcher(txt)//apply the pattern
                .results()//gather the results
                .map(MatchResult::group)//no idea
                .toArray(String[]::new);//export to a string array

        // Then replace all those hexes in the string with a null character
        for( String hex : hexes) { // replace all the hexes with the escape
            try {
                txt = txt.replace(hex, "" + (char) Integer.parseInt(hex.substring(2), 16));
            }catch( NumberFormatException e){
                Logger.error("Failed to convert: "+txt);
            }
        }
        return txt;
    }
    /**
     * Splits a line trying multiple delimiters, first space, then semicolon and
     * then comma
     * 
     * @param line The string to split
     * @return The resulting array
     */
    public static String[] splitList(String line) {
        String[] delims = { " ", "\t", ";", "," };
        String[] eles = { line };
        for (String delim : delims) {
            if (line.contains(delim)) {
                return line.split(delim);
            }
        }
        return eles;
    }

    /**
     * Parses an array with number in ascii format to a byte array
     * 
     * @param base    The base of these number (fe 2, 10 or 16)
     * @param numbers The array to parse
     * @return The resulting byte array
     */
    public static byte[] fromBaseToBytes(int base, String[] numbers) {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        for (int a = 0; a < numbers.length; a++) {
            try {
                if (base == 16) {
                    numbers[a] = numbers[a].replace("0x", "");
                } else if (base == 2) {
                    numbers[a] = numbers[a].replace("0b", "");
                }
                int result = Integer.parseInt(numbers[a], base);
                if (result <= 0xFF) {
                    out.write((byte) result);
                } else {
                    out.write((byte) (result >> 8));
                    out.write((byte) (result % 256));
                }
            } catch (java.lang.NumberFormatException e) {
                Logger.error("Bad number format: " + numbers[a]);
                return new byte[0];
            }
        }
        return out.toByteArray();
    }

    /**
     * Converts an integer to a hex formatted string
     * 
     * @param decimal The number to convert
     * @param bytes   The amount of bytes expected
     * @return String starting with 0x and followed by even amount of uppercase
     *         hexadecimal values
     */
    public static String fromDecToHexString(int decimal, int bytes) {
        StringBuilder hex = new StringBuilder(Integer.toHexString(decimal));

        while (hex.length() % 2 == 1 || hex.length() / 2 < bytes)
            hex.insert(0, "0");
        return "0x" + hex.toString().toUpperCase();
    }

    /**
     * Converts and integer smaller than 256 to a hex formatted string
     * 
     * @param decimal The decimal to convert
     * @return String starting with 0x and followed by even amount of uppercase
     *         hexadecimal values
     */
    public static String fromDecToHexString(int decimal) {
        return fromDecToHexString(decimal, 1);
    }

    public static int[] fromBytesToUnsigned(byte[] bytes) {
        int[] ints = new int[bytes.length];
        for (int a = 0; a < bytes.length; a++) {
            int x = bytes[a];
            ints[a] = x < 0 ? x + 256 : x;
        }
        return ints;
    }

    public static String appendNMEAChecksum(String nmea) {
		int checksum = 0;
		for (int i = 1; i < nmea.length(); i++) {
			checksum = checksum ^ nmea.charAt(i);
		}
		return nmea + "*" + Integer.toHexString(checksum).toUpperCase();
	}

    /**
     * Converts meters to kilometers with the given amount of decimals
     * @param m The amount of meters
     * @param decimals The amount of decimals
     * @return The formatted result
     */
    public static String metersToKm(double m, int decimals) {
        if (m > 5000)
            return roundDouble(m / 1000, 1) + "km";
        return roundDouble(m, decimals) + "m";
    }

    /**
     * Converts meters to feet with an specified amount of decimals
     * @param m The amount of meters
     * @param decimals The amount of decimals
     * @return The formatted result
     */
    public static double metersToFeet(double m, int decimals) {
        return roundDouble(m * 3.2808399, decimals);
    }

    /**
     * Converts meters to fathoms with an specified amount of decimals
     * @param m The amount of meters
     * @param decimals The amount of decimals
     * @return The formatted result
     */
    public static double metersToFathoms(double m, int decimals) {
        return roundDouble(m * 0.546806649, decimals);
    }

    /* ***************************************** * O T H E R *************************************************** */

    /**
     * Determine the beaufort scale according to an average windvelocity
     * 
     * @param minuteAverageWindSpeed Averaged windvelocity
     * @return Current Beaufort level
     */
    public int getBeaufortNr(double minuteAverageWindSpeed) {
        int bf = 0;
        if (minuteAverageWindSpeed < 0.2) {
            bf = 0;
            // beaufortDescr = "Calm";
        } else if (minuteAverageWindSpeed < 1.5) {
            bf = 1;
            // beaufortDescr = "Light Air";
        } else if (minuteAverageWindSpeed < 3.3) {
            bf = 2;
            // beaufortDescr = "Light Breeze";
        } else if (minuteAverageWindSpeed < 5.4) {
            bf = 3;
            // beaufortDescr = "Gentle Breeze";
        } else if (minuteAverageWindSpeed < 7.9) {
            bf = 4;
            // beaufortDescr = "Moderate Breeze";
        } else if (minuteAverageWindSpeed < 10.7) {
            bf = 5;
            // beaufortDescr = "Fresh Breeze";
        } else if (minuteAverageWindSpeed < 13.8) {
            bf = 6;
            // beaufortDescr = "Strong Breeze";
        } else if (minuteAverageWindSpeed < 17.1) {
            bf = 7;
            // beaufortDescr = "High Wind, Moderate Gale";
        } else if (minuteAverageWindSpeed < 20.7) {
            bf = 8;
            // beaufortDescr = "Gale";
        } else if (minuteAverageWindSpeed < 24.4) {
            bf = 9;
            // beaufortDescr = "Strong Gale";
        } else if (minuteAverageWindSpeed < 28.4) {
            bf = 10;
            // beaufortDescr = "Storm";
        } else if (minuteAverageWindSpeed < 32.6) {
            bf = 11;
            // beaufortDescr = "Violent Storm";
        } else {
            bf = 12;
            // beaufortDescr = "Hurricane";
        }
        return bf;
    }

    /**
     * Retrieve the MAC address of an network interface based on the displayname
     * 
     * @param displayname The name of the interface fe. wlan0
     * @return The found MAC or empty string if not found
     */
    public static String getMAC(String displayname) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                // filters out 127.0.0.1 and inactive interfaces
                if (iface.isLoopback() || !iface.isUp())
                    continue;
                String mac = Tools.fromBytesToHexString(iface.getHardwareAddress()).replace(" ", ":");
                mac = mac.replace("0x", "");
                // Logger.info(" MAC for "+iface.getDisplayName()+" -> "+mac);
                if (iface.getDisplayName().equalsIgnoreCase(displayname))
                    return mac;
            }
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
        return "";
    }

    /**
     * Retrieve the IP address of an network interface based on the displayname. If
     * displayname is "" then all info from all interfaces will be returned
     * including mac address
     * 
     * @param displayname The name of the interface fe. wlan0
     * @param ipv4        True if the IPv4 is wanted or false for IPv6
     * @return The found IP or empty string if not found
     */
    public static String getIP(String displayname, boolean ipv4) {
        StringJoiner join = new StringJoiner("\r\n");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                // filters out 127.0.0.1 and inactive interfaces
                if (iface.isLoopback() || !iface.isUp())
                    continue;

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    String p = addr.getHostAddress();
                    if (displayname.isEmpty()) {
                        String mac = Tools.fromBytesToHexString(iface.getHardwareAddress()).replace(" ", ":");
                        mac = mac.replace("0x", "");
                        if ((p.contains(":") && !ipv4) || (p.contains(".") && ipv4))
                            join.add(iface.getDisplayName() + " -> " + p + " [" + mac + "]");
                    } else {
                        if (iface.getDisplayName().equalsIgnoreCase(displayname)) {
                            if ((p.contains(":") && !ipv4) || (p.contains(".") && ipv4))
                                return p;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }
        return join.toString();
    }

    public static String getLocalIP() {
        try (final DatagramSocket socket = new DatagramSocket()) {
            var sock = InetAddress.getByName("8.8.8.8");
            socket.connect(sock, 10002);
            return socket.getLocalAddress().getHostAddress();
        } catch (UnknownHostException | java.net.SocketException | java.io.UncheckedIOException e) {
            Logger.error(e.getMessage());
            return "Unknown";
        }
    }
}
