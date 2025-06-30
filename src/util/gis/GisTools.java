package util.gis;

import org.apache.commons.lang3.math.NumberUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLtools;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.StringJoiner;
import java.util.function.Function;

public class GisTools {

    static final BigDecimal BD60 = new BigDecimal("60.0");

    // static BigDecimal aa = new BigDecimal("6378137.0")
    static final double EE = 6378137.0; // Earth Ellipsoid
    // static BigDecimal ff = new BigDecimal("298.257223563")
    static final double BE = 1.0 / 298.257223563; // Bessel Ellipsoid
    static final double bb = EE * (1.0 - BE);
    static final double cc = EE / (1.0 - BE);
    static final double ea_2 = (EE * EE - bb * bb) / (bb * bb);
    static final double k_0 = 0.9996;
    static final double m0 = 0.9996;

    final static double LAMBDA_0 = 3 * (Math.PI / 180.0);

    private GisTools() {
        throw new IllegalStateException("Utility class");
      }
    /**
     * Converts a coordinate in either deg,min,sec or dig,min to the equivalent amount of degr (double)
     * @param item The coordinate to convert
     * @return The amount in degrees
     */
	public static double convertStringToDegrees( String item ) {
		BigDecimal bd60 = BigDecimal.valueOf(60);
		double degrees=-999;
		
		String[] nrs = item.split(" ");		            	
    	if( nrs.length == 1){//meaning degrees!	 		            				            		
    		degrees = Tools.parseDouble(nrs[0], -999);		            			            		
    	}else if( nrs.length == 3){//meaning degrees minutes seconds!
    		double degs = Tools.parseDouble(nrs[0], -999);
    		double mins = Tools.parseDouble(nrs[1], -999);
    		double secs = Tools.parseDouble(nrs[2], -999);
    		
    		BigDecimal deg = BigDecimal.valueOf(degs);
    		BigDecimal sec = BigDecimal.valueOf(secs);	            		
    		BigDecimal min = sec.divide(bd60, 7, RoundingMode.HALF_UP).add(BigDecimal.valueOf(mins));
    		deg = deg.add(min.divide(bd60,7, RoundingMode.HALF_UP));
    		degrees = deg.doubleValue();
    	}else if( nrs.length == 2){//meaning degrees minutes seconds!
    		double degs = Tools.parseDouble(nrs[0], -999);
    		double mins = Tools.parseDouble(nrs[1], -999);
    		BigDecimal deg = BigDecimal.valueOf(degs);	            		
    		BigDecimal min = BigDecimal.valueOf(mins);
    		deg = deg.add(min.divide(bd60,7, RoundingMode.HALF_UP));
    		degrees = deg.doubleValue();
    	}
    	return degrees;
    }
    /**
     * Convert degrees to the nmea way of displaying it
     * @param degrees The degrees to parse
     * @return The resulting gga compatible formatted degrees
     */
    public static String parseDegreesToGGA( double degrees ){
    	//5529.4463
    	int degr = (int) degrees;
    	BigDecimal di = BigDecimal.valueOf( degrees-degr );
    	
    	double res= Tools.roundDouble( Math.abs(di.multiply(BD60).doubleValue()),5);
    	StringBuilder ress = new StringBuilder("" + Math.abs(res));
    	ress.insert(0, (res < 10 ? "0" : ""));
    	while(ress.length()<8){
    		ress.append("0");
    	}
    	degr = Math.abs(degr);
    	String result = (degr<10?"0":"")+degr;    	
    	return result+ress;    	
    }
    /**
     * Parse the nmea gga way of showing position to a double
     * @param lonlat The position in xx yyyyyy format
     * @param degrdigits amount of characters in lonlat that are for degrees
     * @param orient The orientation of the position N/S or W/E
     * @param resultdecimals How many decimals in the result
     * @return The parsed position
     */
    public static double parseDegrees(String lonlat,int degrdigits, String orient,int resultdecimals){
    	lonlat = lonlat.trim();
    	BigDecimal di = new BigDecimal( lonlat.substring(0,degrdigits) );
    	BigDecimal dc = new BigDecimal( lonlat.substring(degrdigits) ).divide(BD60, resultdecimals, RoundingMode.HALF_UP ).add(di);    	
    	orient=orient.toUpperCase();
    	if( orient.equals("S") || orient.equals("W")){
    		dc = dc.negate();
    	}
		return dc.doubleValue();
    }
     /**
     * Calculate the bearing to follow based on the start coordinates and the
     * destination coordinates
     * 
     * @param fromLon  Start Longitude
     * @param fromLat  Start Latitude
     * @param toLon    Destination Longitude
     * @param toLat    Destination Latitude
     * @param decimals The amount of decimals in the result
     * @return Calculated bearing
     */
    public static double calcBearing(double fromLon, double fromLat, double toLon, double toLat, int decimals) {
        fromLon = Math.toRadians(fromLon);
        fromLat = Math.toRadians(fromLat);
        toLon = Math.toRadians(toLon);
        toLat = Math.toRadians(toLat);
        double y = Math.sin(toLon - fromLon) * Math.cos(toLat);
        double x = Math.cos(fromLat) * Math.sin(toLat)
                - Math.sin(fromLat) * Math.cos(toLat) * Math.cos(toLon - fromLon);
        double d = Math.toDegrees(Math.atan2(y, x));
        return Tools.roundDouble((d + 360) % 360, decimals);
    }

    /**
     * Calculates the distance between two coordinates based on the WGS-84 ellipsoid
     * 
     * @param fromLon Longitude of starting point
     * @param fromLat Latitude of starting point
     * @param toLon Longitude of destination
     * @param toLat Latitutde of destination
     * @param decimals Hpw many decimals in the result
     * @return An array containing {distance in km,distance in nautical miles} or
     *         {Nan,NaN} if it failed
     */
    public static double[] distanceBetween(double fromLon, double fromLat, double toLon, double toLat, int decimals) {
        double[] result = { 0.0, -1.0 };
        // double a = 6378137, b = 6356752.314245, f = 1/298.257223563; // WGS-84
        // ellipsoid params
        double L = Math.toRadians(fromLon - toLon);
        double U1 = Math.atan((1 - BE) * Math.tan(Math.toRadians(toLat)));
        double U2 = Math.atan((1 - BE) * Math.tan(Math.toRadians(fromLat)));
        double sinU1 = Math.sin(U1);
        double cosU1 = Math.cos(U1);
        double sinU2 = Math.sin(U2);
        double cosU2 = Math.cos(U2);

        double lambda = L, lambdaP, iterLimit = 100;
        double sinSigma, cosSqAlpha, cos2SigmaM, cosSigma, sigma;
        double sinAlpha, C;
        do {
            double sinLambda = Math.sin(lambda), cosLambda = Math.cos(lambda);
            sinSigma = Math.sqrt((cosU2 * sinLambda) * (cosU2 * sinLambda)
                    + (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda) * (cosU1 * sinU2 - sinU1 * cosU2 * cosLambda));
            if (sinSigma == 0)
                return result; // co-incident points
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda;
            sigma = Math.atan2(sinSigma, cosSigma);
            sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma;
            cosSqAlpha = 1 - sinAlpha * sinAlpha;
            cos2SigmaM = cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha;
            if (Double.isNaN(cos2SigmaM))
                cos2SigmaM = 0; // equatorial line: cosSqAlpha=0 (§6)
            C = BE / 16 * cosSqAlpha * (4 + BE * (4 - 3 * cosSqAlpha));
            lambdaP = lambda;
            lambda = L + (1 - C) * BE * sinAlpha
                    * (sigma + C * sinSigma * (cos2SigmaM + C * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)));
        } while (Math.abs(lambda - lambdaP) > 1e-12 && --iterLimit > 0);

        if (iterLimit == 0) {
            result[0] = Double.NaN;
            return result; // formula failed to converge
        }
        double uSq = cosSqAlpha * (EE * EE - bb * bb) / (bb * bb);
        double A = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)));
        double B = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)));
        double deltaSigma = B * sinSigma * (cos2SigmaM + B / 4 * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)
                - B / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)));
        double s = bb * A * (sigma - deltaSigma);
        result[0] = Tools.roundDouble(s / 1000, decimals);
        result[1] = Tools.roundDouble(s * 0.000539956803, decimals);
        return result;
    }

    /**
     * Gives an estimate on the distance between coordinates, to smaller the
     * distance, the higher the accuracy. Factor 4 quicker.
     * 
     * @param fromLon Longitude of starting point
     * @param fromLat Latitude of starting point
     * @param toLon Longitude of destination
     * @param toLat Latitutde of destination
     * @param decimals Hpw many decimals in the result
     * @return Distance between the two points in km
     */
    public static double roughDistanceBetween(double fromLon, double fromLat, double toLon, double toLat,
            int decimals) {
        double x = Math.pow(Math.toRadians(toLon - fromLon) * Math.cos(Math.toRadians((fromLat + toLat) / 2)), 2);
        double y = Math.pow(Math.toRadians(toLat - fromLat), 2);
        return Tools.roundDouble(Math.sqrt(x + y) * 6378.137, decimals);
    }

    /**
     * Convert coordinates in GDC to UTM equivalent
     * 
     * @param lat Latitude to convert
     * @param lon Longitude to convert
     * @return UTM coordinates East, North
     */
    public static double[] GDC_To_UTM(double lat, double lon) {
        double N;

        double E;

        double A = 6367449.145882298;
        double B = -16038.508613916465;
        double C = 16.832627271237083;
        double D = -0.0219809069203086;

        double fi = Math.toRadians(lat);
        double cos_fi = Math.cos(fi);
        double dlambda = Math.toRadians(lon) - LAMBDA_0;

        double eta_2 = ea_2 * Math.pow(Math.cos(fi), 2);
        double R = cc / Math.sqrt(1 + eta_2);
        double G = A * fi + B * Math.sin(2 * fi) + C * Math.sin(4 * fi) + D * Math.sin(6 * fi);
        double G_ = R * fi;
        double N_acc = k_0 * R * Math.atan(Math.tan(fi) / Math.cos(dlambda));
        double F = 3.0 * k_0 * R * eta_2 * Math.pow(cos_fi, 3) * Math.sin(fi) / 8.0;
        double E_acc = k_0 * R * Math.log(Math.tan(Math.PI / 4.0 + 0.5 * Math.asin(cos_fi * Math.sin(dlambda))));
        double H = k_0 * R * eta_2 * Math.pow(cos_fi, 3) / 6.0;

        N = N_acc + k_0 * (G - G_) + F * Math.pow(dlambda, 4);
        E = 500000.0 + E_acc + H * Math.pow(dlambda, 3);

        return new double[]{ Tools.roundDouble(E, 2), Tools.roundDouble(N, 2) };
    }
    public static Function<BigDecimal[],BigDecimal> procToUTM( String lat, String lon, Integer[] indexes ){
        int latIndex = lat.startsWith("i")?  NumberUtils.toInt(lat.substring(1), -1) : -1;
        int lonIndex = lon.startsWith("i")?  NumberUtils.toInt(lon.substring(1), -1) : -1;

        var latVal = lat.startsWith("i")? 0 : NumberUtils.toDouble(lat);
        var lonVal = lon.startsWith("i")? 0 : NumberUtils.toDouble(lon);

        int eastIndex,northIndex;
        if(indexes.length==2){
            eastIndex=indexes[0]==-1?latIndex:indexes[0];
            northIndex=indexes[1]==-1?lonIndex:indexes[1];
        }else{
            eastIndex = latIndex;
            northIndex = lonIndex;
        }

        return arr -> {
            var d = GDC_To_UTM( latIndex==-1?latVal:arr[latIndex].doubleValue(),lonIndex==-1?lonVal:arr[lonIndex].doubleValue());
            if( latIndex!=-1)
                arr[eastIndex]=BigDecimal.valueOf(d[0]);
            if( lonIndex!=-1)
                arr[northIndex]=BigDecimal.valueOf(d[1]);
            return BigDecimal.ZERO;
        };
    }
    /**
     * Converts UTM coordinates to GDC equivalent
     * 
     * @param E Easting to convert
     * @param N Northing to convert
     * @return Array containing Latitude,Longitude
     */
    public static double[] UTM_To_GDC(double E, double N) {
        double lon, lat;

        // UTM u = new UTM(false);
        double A = 6364902.166223946;
        double B = 0.0025188265838469916;
        double C = 3.700949701130281E-6;
        double D = 7.447241057128636E-9;

        double fif = (N / A) + (B * (Math.sin(2 * N / A))) + (C * (Math.sin(4 * N / A))) + (D * (Math.sin(6 * N / A)));
        double eta_2f = ea_2 * Math.cos(fif) * Math.cos(fif);
        double Rf = cc / Math.sqrt(1 + eta_2f);
        double E_acc = E - 500000;
        double Y = 2 * Math.atan(Math.exp(E_acc / (m0 * Rf))) - (Math.PI / 2);
        double fi_acc = Math.asin(Math.cos(Y) * Math.sin(fif));
        double H = -(eta_2f * Math.tan(fif) / (2.0 * m0 * Rf * Rf));
        double G = eta_2f * Math.tan(fif) * (1 - (Math.tan(fif) * Math.tan(fif)))
                / (4 * Math.pow(m0, 4) * Math.pow(Rf, 4));
        lat = fi_acc + (H * E_acc * E_acc) + (G * Math.pow(E_acc, 4));

        double dlambda = Math.atan(Math.tan(Y) / Math.cos(fif));
        double I = (-(eta_2f / (3 * Math.pow(m0, 3) * Math.pow(Rf, 3) * Math.cos(fif))));
        lon = LAMBDA_0 + dlambda + (I * Math.pow(E_acc, 3));

        return new double[]{ Tools.roundDouble(Math.toDegrees(lat), 7), Tools.roundDouble(Math.toDegrees(lon), 7) };
    }
    public static String calcOffsetPosition(double heading,double lat, double lon, double lengthOffset,double widthOffset){
        double cosh = Math.cos(Math.toRadians(heading));
        double sinh = Math.sin(Math.toRadians(heading));

        double dE = -widthOffset*cosh+lengthOffset*sinh;
        double dN = lengthOffset*cosh + widthOffset*sinh;

        double[] d = GisTools.GDC_To_UTM(lat, lon);
        d[0] += Tools.roundDouble(dE,3);
        d[1] += Tools.roundDouble(dN,3);

        var response = new StringJoiner("\r\n");
        response.add("Time:"+ TimeTools.formatLongUTCNow());
        response.add("Current: "+lat +"\t"+lon+"\t"+heading+"°\r\n");
        response.add("UTM:\t "+d[0]+"\t"+d[1]+"\r\n");
        response.add("Offset:\t" +Tools.roundDouble(dE,3)+"\t\t"+Tools.roundDouble(dN,3)+"\r\n");
        d = GisTools.UTM_To_GDC(d[0], d[1]);
        response.add("Result:\t " + d[0]+"\t"+d[1]+"\r\n");
        return response.toString();
    }
    public static Function<BigDecimal[],BigDecimal> procToGDC( String east, String northing, Integer[] indexes ){
        int eastIndex = east.startsWith("i")?  NumberUtils.toInt(east.substring(1), -1) : -1;
        int northIndex = northing.startsWith("i")?  NumberUtils.toInt(northing.substring(1), -1) : -1;

        var eastVal = east.startsWith("i")? 0 : NumberUtils.toDouble(east);
        var northVal = northing.startsWith("i")? 0 : NumberUtils.toDouble(northing);

        int latIndex,lonIndex;
        if(indexes.length==2){
            latIndex=indexes[0]==-1?eastIndex:indexes[0];
            lonIndex=indexes[1]==-1?northIndex:indexes[1];
        }else{
            latIndex = eastIndex;
            lonIndex = northIndex;
        }

        return arr -> {
            var d = UTM_To_GDC( eastIndex==-1?eastVal:arr[eastIndex].doubleValue(),northIndex==-1?northVal:arr[northIndex].doubleValue());
            if( latIndex!=-1)
                arr[latIndex]=BigDecimal.valueOf(d[0]);
            if( lonIndex!=-1)
                arr[lonIndex]=BigDecimal.valueOf(d[1]);
            return BigDecimal.ZERO;
        };
    }
    /* ******************************** U N I T  C O N V E R S I O N *********************************************/
    /**
     * Convert the double degrees to degrees , minutes
     *
     * @param degrees  The degrees to convert
     * @param decimals The amount of decimals to keep
     * @param deli     The delimiter to put between the degrees and minutes
     * @return Formatted degrees minutes
     */
    public static String fromDegrToDegrMin(double degrees, int decimals, String deli) {
        BigDecimal bd60 = BigDecimal.valueOf(60);
        BigDecimal deg = BigDecimal.valueOf(degrees);
        int d = (int) degrees;
        BigDecimal[] res1 = deg.divideAndRemainder(BigDecimal.ONE);
        BigDecimal min = res1[1].multiply(bd60);
        if (decimals != -1) {
            double mm = Tools.roundDouble(min.doubleValue(), decimals);
            mm = Math.abs(mm);
            return d + deli + mm;
        } else {
            return d + deli + Math.abs(min.doubleValue());
        }
    }
    /**
     * Converts meters to kilometers with the given amount of decimals
     * @param m The amount of meters
     * @param decimals The amount of decimals
     * @return The formatted result
     */
    public static String metersToKm(double m, int decimals) {
        if (m > 5000)
            return Tools.roundDouble(m / 1000, 1) + "km";
        return Tools.roundDouble(m, decimals) + "m";
    }
    /**
     * Converts meters to feet with an specified amount of decimals
     * @param m The amount of meters
     * @param decimals The amount of decimals
     * @return The formatted result
     */
    public static double metersToFeet(double m, int decimals) {
        return Tools.roundDouble(m * 3.2808399, decimals);
    }
    /**
     * Converts meters to fathoms with an specified amount of decimals
     * @param m The amount of meters
     * @param decimals The amount of decimals
     * @return The formatted result
     */
    public static double metersToFathoms(double m, int decimals) {
        return Tools.roundDouble(m * 0.546806649, decimals);
    }

}