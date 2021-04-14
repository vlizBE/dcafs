package devices;

import com.stream.Writable;
import com.stream.collector.ConfirmCollector;
import org.tinylog.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import util.database.SQLiteDB;
import util.database.SQLiteDB.RollUnit;
import util.tools.TimeTools;
import util.tools.Tools;
import util.xml.XMLtools;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PDM {

    //Needed DAS elements
    Writable pdmLink;

    // Table creation queries

	/*static String PDM_D_TABLE = "CREATE TABLE if not exists 'PDM_D' ('TimeStamp' TEXT NOT NULL,'D1P' REAL,'D1S' REAL,'D2P' REAL,'D2S' REAL"
	            + ",'D3P' REAL,'D3S' REAL,'D4P' REAL,'D4S' REAL,'D5P' REAL,'D5S' REAL,'D6P' REAL,'D6S' REAL,'D7P' REAL,'D7S' REAL,'D8P' REAL,'D8S' REAL);"
	static String PDM_S_TABLE = "CREATE TABLE if not exists 'PDM_S' ('TimeStamp' TEXT NOT NULL,'S1' REAL,'S2' REAL,'S3' REAL,'S4' REAL,'S5' REAL,'S6' REAL,'S7' REAL,'S8' REAL);"
    static String PDM_T_TABLE = "CREATE TABLE if not exists'PDM_titles' ('Label' TEXT NOT NULL, 'title' TEXT NOT NULL);"*/
    
    //DateTimeFormatter formatshort  = DateTimeFormatter.ofPattern("yyMMdd")
    //DateTimeFormatter format  = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    
    DualSlots duals = new DualSlots();
    SingleSlots singles = new SingleSlots();
    long lastUpdate;
    int linkDeads = 0;

    SQLiteDB pdmDB;
    ConfirmCollector confirm;
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    public PDM( Writable pdm ){        
        this(pdm,new SQLiteDB( "pdm", Path.of("db","PDM_"),"yyyy-MM-dd",1,RollUnit.DAY));
    }
    public PDM( Writable pdm, SQLiteDB db ){        
        this.pdmLink = pdm;  
        this.pdmDB=db;
        confirm = new ConfirmCollector("pdm",pdm,scheduler);
        addTables();
    }
    private void addTables(){
        pdmDB.addTableIfNotExists("PDM_D")
                .addTimestamp("TimeStamp").isNotNull()
                .addReal("D1P").addReal("D1S")
                .addReal("D2P").addReal("D2S")
                .addReal("D3P").addReal("D3S")
                .addReal("D4P").addReal("D4S")
                .addReal("D5P").addReal("D5S")
                .addReal("D6P").addReal("D6S")
                .addReal("D7P").addReal("D7S")
                .addReal("D8P").addReal("D8S");

        pdmDB.addTableIfNotExists("PDM_S")
                .addTimestamp("TimeStamp").isNotNull()
                .addReal("S1").addReal("S2").addReal("S3").addReal("S4").addReal("S5").addReal("S6").addReal("S7").addReal("S8");
        pdmDB.addTableIfNotExists("PDM_titles") 
                .addText("Label").isNotNull()
                .addText("title").isNotNull();      
        if( !pdmDB.createContent(true).isEmpty())
            Logger.error("Failed to create the PDM database");
    }
    public void readSettingsFromXML( Document xml ){
        Element powers = XMLtools.getFirstElementByTag( xml, "PDM");
        for( Element el : XMLtools.getChildElements( powers, "output")){        	
        	String label = el.getAttribute("label");        	
        	String title = el.getTextContent();
        	int min = XMLtools.getIntAttribute(el, "mincurrent", 30);
            int max = XMLtools.getIntAttribute(el, "maxcurrent", 500);
            
            int index = Tools.parseInt(label.substring(1), -1 );
            if(index ==-1)
                return;
            if( label.charAt(0)=='D'){
                duals.setCurrentLimits(index, min, max);
                duals.updateTitle(index, title);
            }else{
                singles.setCurrentLimits(index, min, max);
                singles.updateTitle(index, title);
            }
        }
    }

    /********************************************************************************************************/
    /***************************** R E D U N D A N T  O U T P U T *******************************************/
    /********************************************************************************************************/
   /**
    * Get the title associated with the output 
    *
    * @param index The index of the output (1-8)
    * @return The title taken from the settings.xml
    */
    public String getDualTitle( int index ){
        return duals.getTitle(index);
    }    /**
     * Update the current drawn for a dual psu connected output
     * 
     * @param index The index of the output (1-8)
     * @param primary The primary current
     * @param secondary The secondary current
     * @return False if an invalid index was given
     */
    public boolean updateCurrentDual( int index, double primary, double secondary ){
        if( index ==-1 || index > 8 )
            return false;
        duals.updateCurrents(index,primary, secondary);
        return true;
    }
    /**
     * Get current current draw of a specfic redundant output
     * @param index The index of the output (1-8)
     * @return Array containting {primary current,secondary current}
     */
    public double[] getDualCurrent( int index ){
        return duals.currents[index];
    }
    /**
     * Get the current state of a redundant output
     * @param index The index of the output (1-8)
     * @return 0=none active, 1=Primary active, 2=secondary active
     */
    public int getDualState( int index ){
		if( duals.redundancy[index][0] )
			return 1;
		if( duals.redundancy[index][1] )
			return 2;
		return 0;
    }
    public boolean isPrimaryActive( int index ){
        return duals.isPrimaryActive(index);
    }
    public boolean isSecondaryActive( int index ){
        return duals.isSecondaryActive(index);
    }
    /**
     * Use the primary powersupply
     * 
     * @param index The index of the output (1-8)
     */
    public void usePrimary(int index) {
		sendWithConfirm( "D"+index+"P" );
    }
    /**
     * Use the secondary powersupply
     * 
     * @param index The index of the output (1-8)
     */
	public void useSecondary(int index) {
		sendWithConfirm( "D"+index+"S" );
    }
    /**
     * Disable either the primary or the secondary so that both are disabled
     * @param index The index of the output (1-8)
     */
    public void disableDual( int index ) {
		if( getDualState(index)==1 ){
			sendWithConfirm( "d"+index, "*D"+index+"p*");
		}else{
			sendWithConfirm( "d"+index,"*D"+index+"s*" );
		}
		Logger.info("Trying to disable dual "+index);
    }

    public boolean isDualHighCurrent( int index, boolean primary ){
        return duals.currents[index][primary?0:1] > duals.limits[index][1];
    }
    public boolean isDualLowCurrent( int index, boolean primary ){
        return duals.currents[index][primary?0:1] < duals.limits[index][0];
    }
    /********************************************************************************************************/
    /********************************** S I N G L E   O U T P U T *******************************************/
    /********************************************************************************************************/

    /**
     * Update the current draw from a single output
     * 
     * @param index The index of the output (1-8)
     * @param current Current draw in mA
     * @return True if updated, false if invalid index
     */
    public boolean updateCurrentSingle( int index, double current ){
        if( index ==-1 || index > 8 )
            return false;
        singles.updateCurrent(index,current);
        return true;
    }
    /**
     * Enable a single output, if the index is 1 also enable the second one
     * 
     * @param index The index of the output (1-8)
     */
    public void enableSingle( int index ) {
		if( index == 1) {
			sendWithConfirm( "S1" );
			sendWithConfirm( "S2" );
			Logger.info("Heading Hold on");
		}else {
			sendWithConfirm( "S"+index );
		}
		Logger.info("Trying to enable single "+index);
    }
    /**
     * Disable a single output, if the index is 1 also disable the second one
     * 
     * @param index The index of the output (1-8)
     */
    public void disableSingle( int index ) {
		if( index == 1) {
			sendWithConfirm( "s1" );
			sendWithConfirm( "s2" );
			Logger.info("Heading Hold off");
		}else {
			sendWithConfirm( "s"+index );
			Logger.info("Trying to disable single "+index);
		}
    }
    public double getSingleCurrent( int index ){
        return singles.getCurrent(index);
    }
    public boolean isSingleHighCurrent( int index ){
        return singles.getCurrent(index) > singles.limits[index][1];
    }
    public boolean isSingleLowCurrent( int index ){
        return singles.getCurrent(index) < singles.limits[index][0];
    }
    public boolean isSingleActive( int index ){
        return singles.isActive(index);
    }
    public String getSingleTitle( int index ){
        return singles.getTitle(index);
    }
    /********************************************************************************************************/
    /**************************************** G EN E R A L **************************************************/
    /********************************************************************************************************/
    /**
     * Clear all current data, both redunant and single outputs
     */
    public void resetData(){
        for( int a=1;a<9;a++ ){
            duals.updateCurrents(a, -999, -999);
            singles.updateCurrent(a,-999);
        }
    }
    /**
     * Convenience method for sending a command, using standard confirm
     * @param message The command to send
     */
    public void sendWithConfirm( String message ){
        confirm.addConfirm(message,"*"+message+"*");
    }
    /**
     * Convenience method for sending a command
     * @param message The command to send
     * @param confirm The expected reply
     */
    public void sendWithConfirm( String message, String confirm ){
        this.confirm.addConfirm(message,confirm);
    }
    /**
     * Get the Age of the last received data in a readable format
     * @return The last data age in format like 15s or 2m3s etc
     */
    public String getDataAge(){
        return TimeTools.convertPeriodtoString( System.currentTimeMillis()-lastUpdate, TimeUnit.MILLISECONDS);
    }
    /**
     * Check if the last data is less than 10 seconds ago.
     * @return True if last data isn't older than 10s
     */
    public boolean hasRecentData() {
		return System.currentTimeMillis()-lastUpdate < 10000;
	}
    /*********************************************************************************************************/
    /****************************** S U P P L I E S **********************************************************/
    /*********************************************************************************************************/
    /**
     * Get the load on a single PSU
     * @param supply The name of the supply 
     * @return The load in mA
     */
    public double getDualSupplyLoad( String supply ) {
		return duals.getSlotLoad(supply);
    }
    public String getSupplySlot( int id, boolean primary) {
        return duals.getSupplySlot(id, primary);
    }
    /*********************************************************************************************************/
    /*********************************************************************************************************/
    /*********************************************************************************************************/
    /**
     * Process the messag received from the PDM
     * @param message Data datagram
     */
    public void processMessage( String message, LocalDateTime timestamp ){

        String[] items = message.split("\t");
		lastUpdate = System.currentTimeMillis();
		
		for( String item : items) {
            String[] split = item.split(":");
            boolean dual = item.startsWith("D");
			if( dual || item.toUpperCase().startsWith("S")) { // Meaning it's an output				
				if(split.length == 2 ) {
                    double current = Tools.parseDouble(split[1],-1);
                    int index = Tools.parseInt( split[0].substring(1,2), -1);

                    if( dual ){
                        double mult = 1.25;
		                if( index==3 || index==4 || index==7 || index==8 )
			                mult=2;
                        if( split[0].endsWith("P")||split[0].endsWith("p")){
                            duals.updatePrimary(index, current*mult, split[0].endsWith("P"));
                            duals.redundancy[index][0]=split[0].endsWith("P");
                        }else{
                            duals.updateSecondary(index, current*mult,split[0].endsWith("S"));
                        }
                    }else{
                        singles.updateCurrent(index, current*2);
                        singles.setActive( index, item.startsWith("S"));
                    }
                }
                linkDeads=0;
			}else if( item.startsWith("I") ){ // It's the inputs
				storePowers(  );
			}else if( item.equalsIgnoreCase("Link dead?")){
                linkDeads++;
                if( linkDeads==5)
                    resetData();
            }
            
		}
    }
    /**
     * Store the received data in the database
     */
    public void storePowers( ) {
		
        Object[] dual = new Object[17];
        Object[] single = new Object[9];
        
        dual[0]=null;single[0]=null;
        
        for( int a=1;a<9;a++) {
            dual[a]=duals.getPrimary(a);
            dual[a+8]=duals.getSecondary(a);
            single[a]=singles.getCurrent(a);
		}
        
        pdmDB.doDirectInsert( "PDM_D",dual );
        pdmDB.doDirectInsert( "PDM_S",single );
    }    
    /*********************************************************************************************************/
    /*********************************************************************************************************/
    class DualSlots{

        int id;
        String[] slots = {"","slot8","slot9","slot10","slot9","slot10","slot8"};
        String[] titles=new String[9];
        double[][] currents = new double[9][2];
        boolean[][] redundancy = new boolean[9][2];
        double[][] limits = new double[9][2];

        public void updateSupplies( String a1, String a2,String a3, String b1, String b2, String b3 ){
            slots[1]=a1;slots[2]=a2;slots[3]=a3;
            slots[4]=b1;slots[5]=b2;slots[6]=b3;
        }
        public void updateTitle( int index, String title ){
            titles[index]=title;
        }
        public String getTitle(int index ){
            return titles[index];
        }
        public void setCurrentLimits( int index, double min, double max ){
           limits[index][0]=min;
           limits[index][1]=max;
        }
        public void updateCurrents( int index, double primary, double secondary ){
            currents[index][0]=primary;
            currents[index][1]=secondary;
        }
        /* Primary */
        public void updatePrimary( int index, double current,boolean active ){
            currents[index][0]=current;
            redundancy[index][0]=active;
        }
        public double getPrimary(int index ){
            return currents[index][0];
        }
        /* Secondary */
        public double getSecondary(int index ){
            return currents[index][1];
        }
        public void updateSecondary( int index, double current,boolean active ){
            currents[index][1]=current;
            redundancy[index][1]=active;
        }
        /* Slot */
        public double getSlotLoad( String slot ){
            double total=0;
            for( int a=1;a<slots.length;a++ ){
                if( slots[a].equals(slot) )
                    total += getSupplyLoad(a);
            }
            return total;
        }
        public double getSupplyLoad( int supply ){
            switch( supply ) {
                // index 1-4
                case 1: return load(1,0) + load(2,1); 
                case 2: return load(1,1) + load(3,1) + load(4,0);
                case 3: return load(2,0) + load(3,0) + load(4,1);

                case 4: return load(5,0) + load(6,1);
                case 5: return load(5,1) + load(7,1) + load(8,0);
                case 6: return load(6,0) + load(7,0) + load(8,1);
                default: return 0;
            }
        }
        // Supply 1: 1S, 2P, 5P, 7P,8S
        // Supply 2: 1P, 3P, 4S, 6S,7S,8P
        // Supply 3: 2S,3S,4P,5S,6P
        public String getSupplySlot( int id, boolean primary) {
            int base = id<=4?0:3;
            id -= id>4?4:0;
            switch( id ){
                 case 1: return primary?slots[2+base]:slots[1+base];
                 case 2: return primary?slots[1+base]:slots[3+base];
                 case 3: return primary?slots[2+base]:slots[3+base];
                 case 4: return primary?slots[3+base]:slots[2+base];
                 default: Logger.warn("Tried to alter a non-existing slot: "+id); break;
            }
            return "???";
        }

        public double getSingleSupplyLoad( ){
            return singles.getSupplyLoad();
        }
        private double load( int x,int y ){
            return currents[x][y]<0?0:currents[x][y];
        }
        public boolean isPrimaryActive( int index ){
            return duals.redundancy[index][0];
        }
        public boolean isSecondaryActive( int index ){
            return duals.redundancy[index][1];
        }
    }
    static class SingleSlots{

        int id;
        String[] titles=new String[9];
        boolean[] active = {false,false,false,false,false,false,false,false,false};
        double[] currents = new double[9];
        double[][] limits = new double[9][2];

        public void updateTitle( int index, String title ){
            titles[index]=title;
        }
        public String getTitle(int index ){
            return titles[index];
        }
        public void setCurrentLimits( int index, double min, double max ){
           limits[index][0]=min;
           limits[index][1]=max;
        }
        public void updateCurrent( int index, double primary ){
            currents[index] = primary;
        }
        public double getCurrent( int index ){
            return currents[index];
        }
        public double getSupplyLoad(){
            double total=0;
            for( int a=1;a<currents.length;a++)
                total += (currents[a]>=0?currents[a]:0);
            return total;
        }
        public void setActive( int index, boolean active){
            this.active[index]=active;
        }
        public boolean isActive( int index ){
            return this.active[index];
        }
    }
}