package util.gis;

import das.Commandable;
import util.data.DataProviding;
import util.data.DoubleVal;
import io.Writable;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.gis.Waypoint.Travel;
import util.tools.Tools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Waypoints implements Commandable {

    HashMap<String,Waypoint> wps = new HashMap<>();

    Path settingsPath=null;

    static final String XML_TAG = "waypoints";
    static final String XML_TRAVEL = "travel";
    static final String XML_CHILD_TAG = "waypoint";

    DoubleVal latitude;
    DoubleVal longitude;
    DoubleVal sog;

    ScheduledExecutorService scheduler;
    int checkInterval=15;
    BlockingQueue<Datagram> dQueue;

    /* *************************** C O N S T R U C T O R *********************************/
    public Waypoints(Path settingsPath, ScheduledExecutorService scheduler, DataProviding rtvals, BlockingQueue<Datagram> dQueue){
        this.settingsPath=settingsPath;
        this.scheduler=scheduler;
        this.dQueue=dQueue;

        readFromXML(rtvals);

    }

    /* ****************************** A D D I N G ****************************************/
    /**
     * Adding a waypoint to the list
     * @param wp The waypoint to add
     */
    public Waypoint addWaypoint( String id, Waypoint wp ) {
        if(wps.isEmpty())
            scheduler.scheduleAtFixedRate(() -> checkWaypoints(),5,checkInterval, TimeUnit.SECONDS);
    	wps.put(id,wp);
    	return wp;
    }
    public void addWaypoint( String id, double lat, double lon) {
    	addWaypoint(id,lat,lon,50);
    }
    public void addWaypoint( String id, double lat, double lon, double range) {
    	if(wps.isEmpty())
            scheduler.scheduleAtFixedRate(() -> checkWaypoints(),5,checkInterval, TimeUnit.SECONDS);
        wps.put( id, Waypoint.build(id).lat(lat).lon(lon).range(range) );
    }
    public boolean addHere(  String id, double range) {
        if( latitude!= null && longitude != null) {
            addWaypoint(id, latitude.value(), longitude.value(),range);
            return true;
        }
        return false;
    }
    public boolean readFromXML(DataProviding rtvals){
        return readFromXML(rtvals,true);
    }
    public boolean readFromXML( DataProviding rtvals, boolean clear ){
        
        if( settingsPath == null){
            Logger.warn("Reading Waypoints failed because invalid XML.");
            return false;
        }
        if( clear ){
            wps.clear();
        }

        Element wpts = XMLtools.getFirstElementByTag( XMLtools.readXML(settingsPath), XML_TAG);

        if( wpts == null )
            return false;

        if( rtvals!=null) {
            Logger.info("Looking for lat, lon, sog");
            latitude = rtvals.getOrAddDoubleVal(XMLtools.getStringAttribute(wpts, "latval", ""));
            longitude = rtvals.getOrAddDoubleVal(XMLtools.getStringAttribute(wpts, "lonval", ""));
            sog = rtvals.getOrAddDoubleVal(XMLtools.getStringAttribute(wpts, "sogval", ""));
        }

        Logger.info("Reading Waypoints");
        for( Element el : XMLtools.getChildElements(wpts, XML_CHILD_TAG)){
        	if( el != null ){
        		String id = XMLtools.getStringAttribute(el,"id","");
        	    double lat = GisTools.convertStringToDegrees(el.getAttribute("lat"));
        		double lon = GisTools.convertStringToDegrees(el.getAttribute("lon"));
        		String name = XMLtools.getChildValueByTag(el,"name",id);
                double range = Tools.parseDouble( el.getAttribute("range"), -999);

                var wp = addWaypoint( id, Waypoint.build(name).lat(lat).lon(lon).range(range) );

                Logger.info("Checking for travel...");
                for( Element travelEle : XMLtools.getChildElements(el,XML_TRAVEL)){
                    if( travelEle != null ){
                        String idTravel = XMLtools.getStringAttribute(travelEle,"id","");
                        String dir = XMLtools.getStringAttribute(travelEle,"dir","");
                        String bearing = XMLtools.getStringAttribute(travelEle,"bearing","from 0 to 360");

                        wp.addTravel(idTravel,dir,bearing).ifPresent(
                                t -> XMLfab.getRootChildren(settingsPath, "dcafs", "settings", XML_TAG, XML_CHILD_TAG,XML_TRAVEL,"cmd")
                                            .forEach( x -> t.addCmd(x.getTextContent())));
                    }
                }
        	}
        }
        return true;
    }
    /**
     * Write the waypoint data to the file it was read fom originally
     * @param includeTemp Whether or not to also include the temp waypoints
     * @return True if successful
     */
    public boolean storeInXML( boolean includeTemp ){
        if( settingsPath==null){
            Logger.error("XML not defined yet.");
            return false;
        }
        var fab = XMLfab.withRoot(settingsPath,"dcafs","settings");
        fab.digRoot(XML_TAG);
        fab.clearChildren();

        int cnt=0;

        //Adding the clients
        for( Waypoint wp : wps.values() ){
            if( !wp.isTemp() || includeTemp){
                cnt++;
                fab.addParentToRoot(XML_CHILD_TAG)
                        .attr("lat",wp.getLat())
                        .attr("lat",wp.getLon())
                        .attr("range",wp.getRange());
                
                for( Travel tr : wp.getTravels() ){
                    fab.addChild(XML_TRAVEL)
                            .attr("dir", tr.getDirection() )
                            .attr("bearing", tr.bearing )
                            .attr(XML_CHILD_TAG, "" + wp.getName() );
                }
            }
        }
        Logger.info("Stored "+cnt+" waypoints.");

        return fab.build();//overwrite the file
        
    }
    /* ******************************** G E T ********************************************/

    /**
     * Get the waypoint with the given name
     * @param id The id of the waypoint
     * @return An optional waypoint
     */
    public Optional<Waypoint> getWaypoint( String id ) {
    	return Optional.ofNullable(getWaypoint(id,"",false));
    }
    public Waypoint getWaypoint( String id, String name, boolean createIfNew ) {
        var wp = wps.get(id);
        if (wp == null){
            if (createIfNew) {
                wps.put(id, new Waypoint(name));
                return wps.get(id);
            }
            Logger.error("No such waypoint "+id+" in list of "+wps.size());
            return null;
        }else{
            return wp;
        }
    }
    public boolean hasWaypoint( String id ){
        return wps.get(id)!=null;
    }
    /* ****************************** R E M O V E ****************************************/

    /**
     * Remove the waypoint with the given name
     * @param id The name of the waypoint to remove
     * @return True if it was removed
     */
    public boolean removeWaypoint( String id ) {
    	return wps.remove(id)!=null;
    }
    /**
     * Remove all the waypoints that are temporary
     */
    public void clearTempWaypoints(){
        wps.values().removeIf( Waypoint::isTemp );
    }
    /* ******************************** I N F O ******************************************/
    /**
     * Get the amount of waypoints
     * @return The size of the list containing the waypoints
     */
    public int size() {
    	return wps.size();
    }
    /**
     * Check if a waypoint with the given name exists
     * @param id The id to look for
     * @return True if the id was found
     */
    public boolean isExisting( String id ){
        return wps.get(id) != null;
    }
    public boolean isNear( String id ){
        var wp = wps.get(id);
        if( wp==null )
            return false;
        return wp.isNear();
    }
    /**
     * Get an overview off all the available waypoints
     * @param coords Whether to add coordinates
     * @param sog Speed is used to calculate the time till the waypoint
     * @return A descriptive overview off all the current waypoints
     */
	public String getCurrentStates(boolean coords, double sog ){
        StringJoiner b = new StringJoiner("\r\n");
        b.setEmptyValue( "No waypoints yet.");
        if( !wps.isEmpty() ){
            b.add("Current Coordinates: "+latitude +" "+longitude);
        }
    	for( Waypoint w : wps.values())
    		b.add( w.toString(coords, true, sog) );
    	return b.toString();
    }
    public String getWaypointList(String newline ){
        StringJoiner b = new StringJoiner(newline);
        if( wps.isEmpty() )
            return "No waypoints yet.";
        for( Waypoint wp : wps.values() ){
            b.add( wp.getInfo(newline) );
            b.add( wp.toString(false, true, sog.value()) ).add("");
        }
        return b.toString();
    }
    /**
     * Request the closest waypoints to the given coordinates
     * @param lat The latitude
     * @param lon The longitude
     * @return Name of the closest waypoint
     */
    public String getClosestWaypoint( double lat, double lon){
		double dist=10000000;
		String wayp="None";
		for( Waypoint wp : wps.values() ) {
			double d = wp.distanceTo( lat, lon );
			if( d < dist ) {
				dist = d;
				wayp = wp.getName();
			}
		}
		return wayp;
    }

    /**
     * Get the waypoints closest to the current coordinates
      * @return The id found
     */
    public String getNearestWaypoint( ){
        return getClosestWaypoint( latitude.value(),longitude.value());
    }
    /**
     * Check the waypoints to see if any travel occurred, if so execute the commands associated with it
     */
    private void checkWaypoints(){
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        wps.values().forEach( wp -> {
            var travel = wp.checkIt(now, latitude.value(), longitude.value());
            if( travel !=null) {
                travel.getCmds().forEach(cmd -> dQueue.add(Datagram.system(cmd)));
            }
        });
    }

    /**
     * Get the distance to a certain waypoint in meters
     * @param id The id of the waypoint
     * @return The distance in meters
     */
    public double distanceTo(String id){
        var wp = wps.get(id);
        if( wp == null || longitude==null || latitude==null)
            return -1;
        return wp.distanceTo(latitude.value(), longitude.value());
    }
    /* ****************************************************************************************************/
    /* ****************************************************************************************************/
    /**
     * Reply to requests made
     * @param request The request
     * @param wr The writable of the origin of this request
     * @param html Determines if EOL should be <br> or crlf
     * @return Descriptive reply to the request
     */
    @Override
    public String replyToCommand(String[] request, Writable wr, boolean html) {
        
        String[] cmd = request[1].split(",");

		switch( cmd[0] ){
            case "?":
                    StringJoiner b = new StringJoiner(html?"<br>":"\r\n");
                    b.add( "wpts:list -> Get a listing of all waypoints with travel.")
                    .add( "wpts:states -> Get a listing  of the state of each waypoint.")
                    .add( "wpts:reload -> Reloads the waypoints from the settings file.")
                    .add( "wpts:remove,<name> -> Remove a waypoint with a specific name")
                    .add( "wpts:new,<id,<lat>,<lon>,<range> -> Create a new waypoint with the name and coords lat and lon in decimal degrees")
                    .add( "wpts:update,id,lat,lon -> Update the waypoint coords lat and lon in decimal degrees")
                    .add( "wpts:travel,waypoint,bearing,name -> Add travel to a waypoint.");
                    return b.toString();
			case "list": return getWaypointList(html?"<br>":"\r\n");
            case "states":
                if( sog == null)
                    return "Can't determine state, no sog defined";
                return getCurrentStates(false, sog.value() );
            case "store": 
                if( this.storeInXML(false) ){
                    return "Storing waypoints succesful";
                }else{
                    return "Storing waypoints failed";
                }
			case "reload": 
				if( readFromXML(null) ){
					return "Reloaded stored waypoints";
				}else{
					return "Failed to reload waypoints";
                }
            case "addblank":
                XMLfab.withRoot(settingsPath,"dcafs","settings")
                        .addParentToRoot(XML_TAG,"Waypoints are listed here")
                            .attr("lat","lat_rtval")
                            .attr("lon","lon_rtval")
                            .attr("sog","sog_rtval")
                            .addChild(XML_CHILD_TAG)
                                .attr("lat",1)
                                .attr("lon",1)
                                .attr("range",50)
                                    .content("wp_id")
                        .build();
                return "Blank section added";
            case "new": //wpts:new,51.1253,2.2354,wrak
                if( cmd.length < 4)
                    return "Not enough parameters given";
                if( cmd.length > 5)
                    return "To many parameters given (fe. 51.1 not 51,1)";

                double lat = GisTools.convertStringToDegrees(cmd[1]);
                double lon = GisTools.convertStringToDegrees(cmd[2]);
                String id = cmd[3];
                double range = 50;
                if( cmd.length == 5){
                    id=cmd[4];
                    range = Tools.parseDouble(cmd[3], 50);    
                }
                
                addWaypoint( id, lat, lon, range );
                return "Added waypoint called "+cmd[3]+ " lat:"+lat+"??\tlon:"+lon+"??\tRange:"+range+"m";
            case "update":
                if( cmd.length < 4)
                    return "Not enough parameters given wpts:update,id,lat,lon";
                var wpOpt = wps.get(cmd[1]);
                if( wpOpt!=null) {
                    wpOpt.updatePosition(Tools.parseDouble(cmd[2], -999), Tools.parseDouble(cmd[3], -999));
                    return "Updated "+cmd[1];
                }
                return "No such waypoint";
            case "remove":
                if( removeWaypoint( cmd[1]) ) {
                    return "Waypoint removed";
                }else {
                    return "No waypoint found with the name.";
                }
            case XML_TRAVEL:
                Waypoint way = wps.get(cmd[1]);
                if( way == null ){
                    return "No such waypoint: "+cmd[1];
                }
                if( cmd.length != 6)
                    return "Incorrect amount of parameters";
                
                way.addTravel(cmd[5], cmd[4], cmd[2]);
                return "Added travel "+cmd[5]+" to "+ cmd[1];
            default:
                return "Unknown waypoints command";
        }
    }
    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
}