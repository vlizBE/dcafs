package util.gis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import util.math.MathUtils;
import util.tools.Tools;

import org.tinylog.Logger;

public class Positioning {

	int id = -1;
	
	double latitude = -1;
	double longitude = -1;
	
	long timestamp = -1;
	double depth = 0;
	double distanceUpdate=0;
	double heading=0;
	double course;
	String title="";
	Track track = new Track(15);
	
	long sogTime = 12000;
	int cogDistance = 15;

	/***************************************************************************************************************/
	/************************************** C O N S T R U C T O R S ************************************************/
	/***************************************************************************************************************/
	/**
	 * Standard constructor requires an id, latitude and longitude
	 * 
	 * @param id The name to reference the travel with
	 * @param lat The Latitude
	 * @param lon The Longitude
	 */
	public Positioning( int id, double lat,double lon ){
		this.latitude = lat;
		this.longitude = lon;
		this.id = id;
		
		timestamp = Instant.now().toEpochMilli();
	}
		/**
	 * Standard constructor requires an id, latitude and longitude and depth
	 * 
	 * @param id The name to reference the travel with
	 * @param lat The Latitude
	 * @param lon The Longitude
	 * @param depth The current depth
	 */
	public Positioning( int id, double lat,double lon, double depth ){
		this(id,lat,lon);
		this.depth=depth;
	}
	/***************************************************************************************************************/
	/**************************** G E T T E R S  & S E T T E R S ***************************************************/
	/***************************************************************************************************************/
	/**
	 * Set the title
	 * @param title The title to use
	 */
	public void setTitle( String title ){
		if( title != null)
			this.title=title;
	}
	/**
	 * Retrieve the title of this object
	 * @return The title of the object
	 */
	public String getTitle() {
		return this.title;
	}
	/**
	 * Get the ID, mostly used for the GAPS transponders
	 * @return The ID 
	 */
	public int getID() {
		return id;
	}
	/**
	 * Set the measured heading
	 * @param heading The measured heading in degrees
	 */
	public void setHeading( double heading ) {
		this.heading=heading;
	}
	/**
	 * Get the stored heading
	 * @return The heading in degrees
	 */
	public double getHeading( ) {
		return heading;
	}
	/**
	 * Set the current depth
	 * @param depth The depth in meters
	 */
	public void setDepth( double depth ) {
		this.depth=depth;
	}
	/**
	 * Get te latest depth
	 * @return The latest depth in meters
	 */
	public double getDepth() {
		return depth;
	}
	/**
	 * Get the age of the most recent data
	 * @return The age of the most recent data in seconds
	 */
	public int getAge(){
		return (int)(Instant.now().toEpochMilli() - timestamp)/1000;
	}
	/**
	 * Get the latest latitude
	 * @return The latitude in degrees
	 */
	public double getLatitude() {
		return latitude;
	}
	/**
	 * Get the latest longitude
	 * @return The longitude in degrees
	 */
	public double getLongitude() {
		return this.longitude;
	}
	/***************************************************************************************************************/
	/******************************** S E T U P ********************************************************************/
	/***************************************************************************************************************/
	/**
	 * Set the period in which the SoG and descentrate are calculated
	 * @param seconds The window in seconds
	 */
	public void setCogSoGwindow( int seconds ){
		sogTime = seconds* 1000L;
	} 
	/**
	 * Set the maximum age that data in the history can be in seconds
	 * @param maxSeconds The maximum age of data in the track
	 */
	public void setMaxHistoryTime( long maxSeconds ) {
		track.setMaxTime(maxSeconds);
	}
	/***************************************************************************************************************/
	/******************************** A D D I N G  D A T A *********************************************************/
	/***************************************************************************************************************/
	/**
	 * Update the coordinates
	 * @param lat The new latitude 
	 * @param lon The new longitude
	 * @return The distance travelled (in meters) 
	 */
	public synchronized double updatePosition(double lat, double lon) {
		return updatePosition(lat,lon,0.0);
	}
	/**
	 * Set the current coordinates and depth
	 * @param lat The new latitude 
	 * @param lon The new longitude
	 * @param depth The latest depth
	 * @return Distance travelled (in meters) since last position
	 */
	public synchronized double updatePosition(double lat, double lon, double depth ){
		distanceUpdate = GisTools.distanceBetween(this.longitude, this.latitude, lon,lat, 4)[0];
		this.latitude = lat;
		this.longitude = lon;
		this.depth=depth;
		
		track.addPosition(lon, lat, depth);
		
		timestamp = System.currentTimeMillis();
		return distanceUpdate;
	}
	/***************************************************************************************************************/
	/***************************************************************************************************************/
	/***************************************************************************************************************/
	/**
	 * Get the latest coordinates in a string showing degrees-minutes
	 * @return String with lat - lon in degrees minutes
	 */
	public String getPositionString(){
		return GisTools.fromDegrToDegrMin(latitude, 6, "°")+" "+
				GisTools.fromDegrToDegrMin(longitude, 6, "°");
	}
	/***************************************************************************************************************/
	/***************************************************************************************************************/
	/***************************************************************************************************************/
	/**
	 * Generate a WPL sentence based on this positioning
	 * 
	 * @return The generated WPL sentence
	 */
	public String generateWPL(){
		/*
		 * WPL Waypoint Location
				1      2   3      4   5   6
				|      |   |      |   |   |
			$--WPL,llll.ll,a,yyyyy.yy,a,c--c*hh
			1) Latitude
			2) N or S (North or South)
			3) Longitude
			4) E or W (East or West)
			5) Waypoint Name
			6) Checksum
		 */
		
		String lat = GisTools.parseDegreesToGGA(this.latitude)+","+(this.latitude>0?"N":"S")+",";		
		String lon = GisTools.parseDegreesToGGA(this.longitude)+","+(this.longitude>0?"E":"W")+",";
		if( longitude < 100)
			lon = "0"+lon;
		String result = "$GPWPL,"+lat+lon+(title.isBlank()?"gaps"+id:title)+"*";
		result += MathUtils.getNMEAchecksum(result+"00");
		return result;
	}
	/***************************************************************************************************************/
	/************************************ C A L C U L A T I O N S **************************************************/
	/***************************************************************************************************************/
	/**
	 * Get the distance between this position and the given one (in meters)
	 * @param to The position to calculate the distance to
	 * @return The distance between the two points in meters.
	 */
	public double distanceTo( Positioning to) {
		double d= GisTools.distanceBetween(longitude, latitude, to.getLongitude(), to.getLatitude(), 3)[0]*1000;
		return Tools.roundDouble(d, 1);
	}
	/**
	 * Get the distance between this Positioning and the given coordinates 
	 * @param lon The longitude of the target
	 * @param lat The latitute of the target
	 * @return The distance in meters between this Position and the target
	 */
	public double distanceTo( double lon, double lat ) {
		double d= GisTools.distanceBetween(longitude, latitude, lon, lat, 3)[0]*1000;
		return Tools.roundDouble(d, 1);
	}
	/**
	 * Calculate the bearing that needs to be followed to go to the target coordinates
	 * @param lon The longitude of the target in decimal degrees
	 * @param lat The latitude of the target in decimal degrees
	 * @param decimals The amount of decimals
	 * @return The calculated bearing
	 */
	public double bearingTo( double lon, double lat, int decimals ) {
		return GisTools.calcBearing(longitude, latitude, lon, lat, decimals );
	}
	/**
	 * Calculate the bearing that needs to be followed to go to the target coordinates
	 * @param to The target Positioning
	 * @return The calculated bearing
	 */
	public double bearingTo( Positioning to) {
		return GisTools.calcBearing(longitude, latitude, to.getLongitude(), to.getLatitude(), 1);
	}
	/**
	 * Return calculatd SoG
	 * @return Array containing SoG in {m/s,Kn}
	 */
	public double[] getSoG() {
		return track.getSOG();
	}
	/**
	 * Get the descentrate during the followed track during the same period as the SoG
	 * @param decimals Amount of decimals in the answer, unit is meters
	 * @return The descentrate in meters per second 
	 */
	public double getDescentRate(int decimals) {
		return Tools.roundDouble( track.getDescentRate(), decimals);
	}
	/**
	 * Get the calculated CoG based on the last x meters covered (default 15m)
	 * @return The calculated CoG
	 */
	public double getCoG() {
		if( track == null){
			Logger.warn("Track still null.");
			return -999;
		}
		return track.getCoG();
	}
	/***************************************************************************************************************/
	/******************************** H I S T O R Y / T R A C K ****************************************************/
	/***************************************************************************************************************/
	/**
	 * Retrieve the stored past coordinates
	 * @return List of the stored coordinates
	 */
	public synchronized List<double[]> getHistory() {
		return track.coords;
	}

	/**
	 * Clear the stored history
	 */
	public synchronized void clearHistory() {
		track.coords.clear();
		track.timestamps.clear();
	}
	/**
	 * Calculate the total distance travelled in the stored history.
	 * @return The total distance in {km,nm}
	 */
	public double[] getTrackDistance(){
		double[] d = track.getTotalDistance();
		d[0] = Tools.roundDouble(d[0], 3);
		d[1] = Tools.roundDouble(d[1], 3);
		return d;
	}
	/**
	 * Class tat holds the historical data
	 */
	public class Track{
		ArrayList<double[]> coords = new ArrayList<>();
		ArrayList<Long> timestamps = new ArrayList<>();
		long maxTime=30000;
		
		public Track( long seconds ) {
			maxTime = seconds*1000;
		}
		public void setMaxTime( long seconds ) {
			this.maxTime = seconds*1000;
		}
		public synchronized void addPosition(double lon, double lat) {
			addPosition( lon, lat, 0.0);
		}
		public synchronized void addPosition(double lon, double lat, double depth) {
			coords.add(new double[] {lon,lat,depth});
			timestamps.add(System.currentTimeMillis());
			
			int a=0;
			while( a < coords.size() ) {
				long t = timestamps.get(a);
				if( System.currentTimeMillis()- t > maxTime || coords.size() > (maxTime/1000+10)) {
					coords.remove(a);
					timestamps.remove(a);
				}else {
					a++;
				}
			}
		}
		public double getDescentRate() {
			try {
				if( coords.size() <5)
					return 0;
				
				double start = -1;
				double end = timestamps.get(timestamps.size()-1);
				double dist = 0;
				
				for( int a=0;a<coords.size()-1;a++) {
					if(  System.currentTimeMillis() - timestamps.get(a) < sogTime ) {
						double[] from = coords.get(a);
						double[] to = coords.get(a+1);
						if( from[2]!=-1 && to[2]!=-1) {
							dist +=  from[2]-to[2];
						}
						if( start ==-1)
							start=timestamps.get(a);
					}
				}
				
				double period = Math.abs(end-start)/1000; // seconds
				return Tools.roundDouble(dist/period,3);	
			}catch( IndexOutOfBoundsException | NullPointerException d) {
				Logger.error(d);
			}
			return 0;
		}
		/**
		 * Return calculatd SoG
		 * @return Array containing SoG in m/s and Kn
		 */
		public double[] getSOG() {
			try {
				if( coords.size() <5)
					return new double[]{-1,-1,-1};
				
				double start = -1;
				double end = timestamps.get(timestamps.size()-1);
				double[] dist;
				double[] to = coords.get(coords.size()-1);
				double[] from = {0,0};
	
				for( int a=coords.size()-2;a>-1;a--) {
					if(  System.currentTimeMillis() - timestamps.get(a) < sogTime ) {
						from = coords.get(a);	
						start=timestamps.get(a);
					}else{
						break;
					}
				}
				dist =  GisTools.distanceBetween(from[0], from[1], to[0], to[1], 6);
				dist[0] *= 1000;
				double period = Math.abs(end-start)/1000; // seconds
				double ms = Tools.roundDouble( dist[0]/period,2 );
				double kn = Tools.roundDouble( 3600*(dist[1]/period),2 );

				if( Double.isFinite(ms) || Double.isNaN(ms)){
					ms=0;kn=0;
				}
				return new double[]{ms,kn};		
			}catch( IndexOutOfBoundsException | NullPointerException d) {
				Logger.error(d);
			}
			return new double[]{-1,-1};
		}
		public double[] getTotalDistance(){
			double[] dist = {0,0};
			if( coords.isEmpty())
				return dist;

			for( int a=0;a<coords.size()-1;a++) {
				double[] from = coords.get(a);
				double[] to = coords.get(a+1);
				
				double[] d =  GisTools.distanceBetween(from[0], from[1], to[0], to[1], 6);
				dist[0] +=d[0];
				dist[1] +=d[1];
			}
			return dist;
		}
		public double getCoG() {
			if( coords.size() < 5)
				return -999;
			int x=-1;
			double[] to = coords.get(coords.size()-1); //latest position
			double dis=0;

			for( int a=coords.size()-2; coords.size()>2 && a>-1 ;a--) {
				double[] from = coords.get(a);
				double[] d =  GisTools.distanceBetween(from[0], from[1], to[0], to[1], 6);
				
				dis = d[0]*1000;

				if( dis > cogDistance && x==-1){
					x=a;
					break;
				}
			}
			
			if( x != -1) {
				double[] from = coords.get( x );
				return GisTools.calcBearing(from[0], from[1], to[0], to[1], 1);
			}
			return -999;
		}
	}
}
