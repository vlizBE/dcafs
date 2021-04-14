package util.tools;

import org.apache.commons.lang3.tuple.Pair;
import org.tinylog.Logger;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class TimeTools {

    static final public DateTimeFormatter LONGDATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
	static final String SHORTDATE_STRING = "yyyy-MM-dd HH:mm:ss";
	static final String NMEADATE_STRING = "yyyy-MM-dd HHmmss.SS";
	static final String NMEASHORT_STRING = "yyyy-MM-dd HHmmss";
    static final DateTimeFormatter NMEA_FORMATTER = DateTimeFormatter.ofPattern(NMEADATE_STRING);
    static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    

    private TimeTools(){
        throw new IllegalStateException("Utility class");
    }
    /**
     * This converts a string representation of a timestamp to a different string representation
     * @param date The input timestamp
     * @param inputFormat The format of the input timestamp
     * @param outputFormat The desired format for the timestamp
     * @return The input timestamp with the desired output format
     */
    public static String reformatDate(String date, String inputFormat, String outputFormat) {
        try{
            LocalDateTime dt = LocalDateTime.parse(date, DateTimeFormatter.ofPattern(inputFormat));
            if (dt != null) {
                return dt.format( DateTimeFormatter.ofPattern(outputFormat) );
            }
        }catch(DateTimeParseException e){
            Logger.error(e);
        }
        return "";
    }
    public static String reformatTime(String date, String inputFormat, String outputFormat){
        try {
            LocalTime dt = LocalTime.parse(date, DateTimeFormatter.ofPattern(inputFormat));
            if (dt != null) {
                return dt.format(DateTimeFormatter.ofPattern(outputFormat));
            }
            return "";
        }catch(DateTimeParseException e){
            Logger.error(e);
        }
        return "";
    }
    /**
     * Parses a given date+time with a given format to a localdatetime
     * @param dt The given datetime
     * @param format The formate the datetime is in
     * @return Parsed datetime
     */
    public static LocalDateTime parseDateTime( String dt , String format ){
        return LocalDateTime.parse(dt, DateTimeFormatter.ofPattern(format));
    }
    /**
     * Takes a millis since epoch and creates a string in yyyy-MM-dd HH:mm:ss.SSS format
     * @param timestamp The timestamp in millis
     * @return If successful it returns the requested date, if not an empty string
     */
    public static synchronized String formatLongDate(long timestamp) {
        if( timestamp <= 0)
            return "";   
        try{                	
            
        	return LONGDATE_FORMATTER.format( Instant.ofEpochMilli(timestamp));
        }catch( java.lang.ArrayIndexOutOfBoundsException e ){
        	Logger.error("Error trying to format date.");
        	return "";
        }
    }
    /**
     * Gets the current datetime and formats it
     * @param outputFormat The format to use eg. yy/MM/dd HH:mm
     * @return If successful it returns the requested date, if not an empty string
     */
    public static String formatNow(String outputFormat) {
        return formatNow(outputFormat, 0);
    }
    public static String formatUTCNow(String outputFormat) {
    	 DateFormat outFormat = new SimpleDateFormat(outputFormat);
         outFormat.setTimeZone(UTC);
         return outFormat.format( System.currentTimeMillis() );
    }
    /**
     * Gets the current UTC datetime and formats it according to the standard 'long' format yyyy-MM-dd HH:mm:ss.SSS
     * @return If successful it returns the requested date, if not an empty string
     */
    public static String formatLongUTCNow( ) {
    	OffsetDateTime utc = OffsetDateTime.now(ZoneOffset.UTC);    	
        return utc.format(LONGDATE_FORMATTER);
    }
    public static String formatLongNow( ) {
    	LocalDateTime local = LocalDateTime.now();    	
        return local.format(LONGDATE_FORMATTER);
    }
    /**
     * Gets the current datetime and adds an offset in days and formats it
     * @param outputFormat The format to use eg. yy/MM/dd HH:mm
     * @param offset Amount of days offset (can be positive or negative)
     * @return If successful it returns the requested date, if not an empty string
     */
    public static String formatNow(String outputFormat, int offset) {
    	LocalDateTime local = LocalDateTime.now();
        if( offset > 0){
           local=local.plusDays(offset);
        }else{
            local=local.minusDays(Math.abs(offset));
        }
        
        return local.format( DateTimeFormatter.ofPattern(outputFormat) );
    }
    /**
     * Takes a string datetime and converts it to a calendar object
     * @param date The datetime in string of format 'yyyy-MM-dd HH:mm:ss.SSS'
     * @return If successful it returns the requested date in UTC , if not -1
     */
    public static synchronized long parseLongToMillis(String date) {                
        if( date.isBlank())
        	return -1;
    	try {    		
            return LocalDateTime.parse( date, LONGDATE_FORMATTER ).toInstant( ZoneOffset.UTC ).toEpochMilli(); 
        } catch (java.lang.NumberFormatException ex1) {
        	Logger.error("Failed to parseToMillis: " + date+" with inputformat yyyy-MM-dd HH:mm:ss.SSS " +ex1);
            return -1;
        }
    }
    /**
     * Get the current day of the week object
     * @return Current day
     */
    public static DayOfWeek getCurrentWeekDay(){
    	return LocalDateTime.now(ZoneOffset.UTC).getDayOfWeek();    
    }
    /**
     * Convert the string repr of a time unit to the time unit object
     * @param unit The string to convert
     * @param def The time unit to return in case of failure
     * @return The resulting time unit
     */
    public static TimeUnit getTimeUnit( String unit, TimeUnit def ){
    	if( unit.isBlank())
    		return def;
    	return getTimeUnit(unit);
    }
    /**
     * Tries to convert the string representation of a time unit to the actual time unit
     * @param unit The string to convert
     * @return The resulting time unit
     */
    public static TimeUnit getTimeUnit( String unit ){
    	switch( unit ){
    		case "millis":case "milli": return TimeUnit.MILLISECONDS;
    		case "seconds": case "sec": case "s":   return TimeUnit.SECONDS;
    		case "minutes": case "min": case "m":   return TimeUnit.MINUTES;
    		case "hour": case "h": return TimeUnit.HOURS;
    		default: return TimeUnit.DAYS;
    	}
    }
    /**
     * Convert a certain amount of time units to the same amount of seconds
     * @param time The amount to convert
     * @param unit THe time unit of which 'time' is
     * @return The same amount of seconds
     */
    public static long convertPeriodtoSeconds( long time, TimeUnit unit ){
    	switch( unit ){
			case DAYS: return time*3600*24;
            case HOURS: return time * 3600;
            case MINUTES: return time*60;
            case SECONDS: return time; 
            case MILLISECONDS: return time/1000;
			case MICROSECONDS: return time/1000000;			
			default: return -1;
    	}
    }
    /**
     * Parses a period formatted for a Task to a Duration
     * 
     * @param period The string to format
     * @return The duration of the string or a zero duration if something went wrong
     */
    public static Duration parseTaskPeriodToDuration( String period ){

        Duration duration = Duration.ofSeconds(0);

        period = period.toUpperCase().replace("SEC", "S").replace("MIN", "M");

        int h = period.indexOf("H");
    	int m = period.indexOf("M");
        int s = period.indexOf("S");
        int ms = period.indexOf("MS");
        
        try {
	    	if( h != -1 ){ 
                duration = duration.plusHours( Integer.parseInt( period.substring(0, h) ) );	    		
	    		h+=1;
	    	}else{
	    		h=0;
	    	}
	    	if( m !=- 1 && m != ms){
                duration = duration.plusMinutes( Integer.parseInt(period.substring(h, m) ) );	    
	    		m+=1;
	    	}else{
	    		m=h;
	    	}
	    	if( s!= -1 && s!=ms+1){
                duration = duration.plusSeconds( Integer.parseInt( period.substring(m, s) ) );	    	
            }else{
                s=m;
            }    
            if( ms!= -1){               
                duration = duration.plusMillis( Integer.parseInt( period.substring(s, ms) ) );                            
            }
            return duration;	
	    }catch( java.lang.ArrayIndexOutOfBoundsException e) {
			Logger.error("Error parsing period to seconds:"+period);
        }
        return Duration.ZERO;
    }
    /**
     * Parses the given time period to the equivalent amount of TimeUnits
     * @param period The period that needs to be parsed
     * @return The equivalent amount of seconds
     */
    private static long parsePeriodString( String period, TimeUnit unit ){
               
        period = period.toUpperCase().replace("SEC", "S").replace("MIN", "M");
        period = period.replace("DAY","D").replace("DAYS","D");
        period = period.replace(" ",""); // remove spaces
        int d = period.indexOf("D");
        int h = period.indexOf("H");
    	int m = period.indexOf("M");
        int s = period.indexOf("S");
        int ms = period.indexOf("MS");
        long total=0;
        
    	try {
            if( d != -1 ){ // If H is present in the string
                total += Tools.parseInt( period.substring(0, d), 0 ); // get the number in front and multiply for h->s
                d+=1;// increment the index to skip te letter
            }else{
                d=0; // No h found, so change the index to 0 to mimic it
            }
            total *= 24L; // Go from days to hours
	    	if( h != -1 ){ // If H is present in the string
	    		total += Tools.parseInt( period.substring(d, h), 0 ); // get the number in front and multiply for h->s
	    		h+=1;// increment the index to skip te letter
	    	}else{
	    		h=d; // No h found, so change the index to 0 to mimic it
	    	}
	    	total *= 60L; // Go from hours to minutes
	    	if( m !=- 1 && m != ms){ // if M for minute found and the M is not part of ms
	    		total += Tools.parseInt(period.substring(h, m), 0);
	    		m+=1;// increment the index to skip te letter
	    	}else{
	    		m=h; // no M found so last was h (eg. 1h10s)
	    	}
            total *= 60L; // Go from minutes to seconds
	    	if( s!= -1 && s!=ms+1){ // If S for second found but not as part of ms
	    		total += Tools.parseInt( period.substring(m, s), 0 );
            }else{
                s=m;
            }
	    	// Now total should contain the converted part in seconds, millis not yet included
            if( ms!= -1){
                int millis = Tools.parseInt( period.substring(s, ms), 0 );
                if( unit == TimeUnit.SECONDS ){
                    total += millis/1000;   // Users asked seconds, so add rounded
                }else if (unit == TimeUnit.MILLISECONDS ){
                    total = total*1000+millis;
                }                              
            }else if( unit == TimeUnit.MILLISECONDS ){
                // No ms in the string, so multiple the earlier summed seconds
                return total*1000;
            }
	    }catch( java.lang.ArrayIndexOutOfBoundsException e) {
			Logger.error("Error parsing period to seconds:"+period);
        }
    	return total;
    }

    public static Pair<Long,TimeUnit> inShortestTimeUnit( Pair<Long,TimeUnit> first, Pair<Long,TimeUnit> sec ){
        if( first.getValue().compareTo( sec.getValue()) > 0) {
            first = increasePeriodTimeUnit(first, sec.getValue());
        }
        return first;
    }
    public static Pair<Long,TimeUnit> increasePeriodTimeUnit( Pair<Long,TimeUnit> from, TimeUnit to){
        return increasePeriodTimeUnit( from.getKey(), from.getValue(), to);
    } 
    public static Pair<Long,TimeUnit> increasePeriodTimeUnit( long value, TimeUnit from, TimeUnit to){
        switch( from ){
            case DAYS: value *= 24*60*60*1000; break;
            case HOURS: value *= 60*60*1000; break;
            case MINUTES:value *= 60*1000; break;
            case SECONDS: value *= 1000; break;   
            case MILLISECONDS:  value *= 1; break;
            default:  break;      
        }
         switch( to ){
            case HOURS: value /= 60*60*1000; break;
            case MINUTES:value /= 60*1000; break;
            case SECONDS: value /= 1000; break;  
            default:  break;   
         }
         return Pair.of(value,to);
    }
    public static Pair<Long,TimeUnit> parsePeriodString( String period ){
        
        period = period.toUpperCase();
        period = period.replace("DAYS", "D");
        period = period.replace("HOURS", "H");
        period = period.replace("SEC", "S");
        period = period.replace("MIN", "M");
        int d = period.indexOf("D");
        int h = period.indexOf("H");
    	int m = period.indexOf("M");
        int s = period.indexOf("S");
        int ms = period.indexOf("MS");
        long total=0;
        
    	try {
            // Days
            if( d != -1 ){    		
	    		total += Tools.parseInt( period.substring(0, d), 0 );
                d+=1;
                if( d==period.length() ){
                    return Pair.of(total,TimeUnit.DAYS);
                }
	    	}else{
	    		d=0;
            }
            total *= 24;
            // Hours
	    	if( h != -1 ){    		
	    		total += Tools.parseInt( period.substring(d, h), 0 );
                h+=1;
                if( h==period.length() ){
                    return Pair.of(total,TimeUnit.HOURS);
                }
	    	}else{
	    		h=0;
            }
            total *= 60; //in minutes
            // Minutes
	    	if( m !=- 1 && m != ms){
	    		total += Tools.parseInt(period.substring(h, m), 0);
                m+=1;
                if( m==period.length() ){
                    return Pair.of(total,TimeUnit.MINUTES);
                }
	    	}else{
	    		m=h;
            }
            total *= 60; //in seconds

	    	if( s!= -1 && s!=ms+1){
                total += Tools.parseInt( period.substring(m, s), 0 );
                s++;
                if( s==period.length() ){
                    return Pair.of(total,TimeUnit.SECONDS);
                }
            }else{
                s=m;
            }    
            total *= 1000; //in millis
            if( ms!= -1){ 
                total += Tools.parseInt( period.substring(s, ms), 0 );                      
            }	
            return Pair.of(total,TimeUnit.MILLISECONDS);
	    }catch( java.lang.ArrayIndexOutOfBoundsException e) {
            Logger.error("Error parsing period to seconds:"+period);
            return Pair.of(total,null);
        }   
    }
    public static int parsePeriodStringToSeconds( String period ){
        return (int)parsePeriodString(period, TimeUnit.SECONDS);
    }
    public static long parsePeriodStringToMillis( String period ){
    	return parsePeriodString(period, TimeUnit.MILLISECONDS);   	
    }
    /**
     * Converts an amount of time unit to a string period
     * @param amount The amount of the time unit    
     * @param unit The time unit of the amount
     * @return The string formatted period
     */
	public static String convertPeriodtoString( long amount, TimeUnit unit ){		
		boolean round=false;
		if( unit == TimeUnit.MILLISECONDS ){
			if( amount < 5000 ){
				return amount+"ms";
			}
			amount /= 1000; //seconds
			unit = TimeUnit.SECONDS;
		}
		if( unit == TimeUnit.SECONDS ){
			if( amount < 90 ){
				return amount+"s";
			}else if( amount < 3600 ){
				return (amount-amount%60)/60+"m"+(amount%60==0?"":amount%60+"s");
			}
			if( amount % 60 > 30 )
				round=true;
			amount /= 60; //minutes
			if(round)
				amount++;
			round=false;
			unit = TimeUnit.MINUTES;
		}
		long min=0;
		if( unit == TimeUnit.MINUTES ){
			if( amount < 120 ){
				return amount+" min";
			}else if( amount < 1440 ){
				return (amount-amount%60)/60+"h"+(amount%60==0?"":amount%60+"m");
			}
			
			if( amount % 60 > 30 )
				round=true;
			min=amount%60;
			amount /= 60;
			if(round)
				amount++;
			unit = TimeUnit.HOURS;
		}
		if( unit == TimeUnit.HOURS ){
            if( amount > 24*7 || amount%24==0){
                return amount/24+" day"+(amount!=1?"s":"");
            }else if( amount > 24 ){
				return amount/24+"d "+amount%24+"h";
			}else if( amount > 12){
				return amount+" hour"+(amount!=1?"s":"");
			}else{
                return amount+" h "+min+"m";
            }
		}
		return amount+"h";
    }
    /*********************** C A L C U L A T I O N S *******************************************/
    /**
     * Calculates the seconds till a certain time in UTC
     * @param time The time in standard format
     * @return Amount of seconds till the given time
     */
    public static long calcSecondsTo( String time ){
        OffsetTime now = OffsetTime.now(ZoneOffset.UTC);
        OffsetTime then = LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm:ss")).atOffset(ZoneOffset.UTC);
        if( now.isBefore(then) ){
            return Duration.between(now,then).toSeconds();
        }else{
            return Duration.between(then,now).toSeconds()+86400;
        }
    }   
    /**
     * Calculates the seconds to midnight in UTC
     * @return Seconds till midnight in UTC
     */
    public static long secondsToMidnight(){
        OffsetTime midnight = OffsetTime.of(23, 59, 59, 0, ZoneOffset.UTC);
        return Duration.between( OffsetTime.now(ZoneOffset.UTC),midnight).toSeconds()+1;
    }
}