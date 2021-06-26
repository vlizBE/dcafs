package das;

import com.email.Email;
import org.tinylog.Logger;
import util.task.TaskManager;
import util.tools.TimeTools;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class IssueCollector {

    HashMap<String,Issue> issues = new HashMap<>();
    HashMap<String,Resolved> resolveds = new HashMap<>();

    /* Delayed stuff */
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(); // scheduler

    /* Notify */
	BlockingQueue<Email> emailQueue;
	BlockingQueue<String[]> smsQueue;

    BlockingQueue<String> sqlQueue;

	static String longDate = "yyyy-MM-dd HH:mm:ss.SSS";
	static DateTimeFormatter longFormat = DateTimeFormatter.ofPattern(longDate);
    static DateTimeFormatter secFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    public enum ALARM_TYPE {SMS,EMAIL}

    TaskManager alarms;

    public boolean hasAlarms(){
        return alarms!=null;
    }
    public void setAlarmsTaskManager( TaskManager tm){
        alarms=tm;
        alarms.disableStartOnLoad(); // Don't start the tasks without an actual alarm
    }

    /* **********************************************************************************************************/
    /**
     * Check whether the issue is currently active or nor
     * @param id The id of the issue
     * @return True if active
     */
    public boolean isActive( String id ){
        Issue i = issues.get(id);
        if( i == null ){
            Logger.warn("No such issue: "+id);
            Logger.info("Issues: "+this.getIssueOverview(false));
            return false;
        }
        return i.isActive();
    }
    /* **********************************************************************************************************/
    /**
     * Report an issue
     * @param id The id of the issue
     * @param message A clear message describing the issue
     * @param active Whether or not the issue is currently active
     * @return True if its active, false if it isn't
     */
    public boolean reportIssue( String id, String message, boolean active ){
        return reportIssue(id,message,LocalDateTime.now(ZoneOffset.UTC),active);
    }
    public boolean reportIssue( String id, String message, LocalDateTime time, boolean active ){
        if( active ){
            return activateIssue( id, message, time );
        }else{
            return resolveIssue( id, message, time );
        }
    }
    public boolean reportIssue( String id, String message, boolean good, boolean bad ){

        if( bad ){
            return activateIssue( id, message, LocalDateTime.now(ZoneOffset.UTC) );
        }else if( good ) {
            return resolveIssue( id, message, LocalDateTime.now(ZoneOffset.UTC) );
        }
        return false;
    }
    public boolean reportIssue( String id, String message, LocalDateTime time, boolean good, boolean bad ){
        if( bad ){
            return activateIssue( id, message, time );
        }else if( good ) {
            return resolveIssue( id, message, time );
        }
        return false;
    }
    /**
     * For issues that are just in instance instead of a period, for example a test failed.
     * 
     * @param id The ID of the issue, is used to check for a task to run
     * @param message The message explaining the issue
     * @param time When the issue occurred
     */
    public void triggerIssue( String id, String message, LocalDateTime time ){
        activateIssue(id,message,time);
        
        Issue i = issues.get(id);
        i.deActivate(time);

        Resolved r = resolveds.get(id);
        if( r != null ){
            r.addInstance( i.getStart(), Duration.between(i.getStart(), i.getEnd()).getSeconds() );
        }else{
            resolveds.put( id, new Resolved(i.getStart(), Duration.between(i.getStart(), i.getEnd()).getSeconds()));
        }
    }
    public int getIssueTriggerCount( String issue ){
        Resolved r = resolveds.get(issue);
        return r==null?1:r.getCount();
    }
    private boolean activateIssue( String id, String message, LocalDateTime dt ){
        Issue i = issues.get(id);
        if( i != null ){
            if( i.isActive() ){
                return false;
            }else{
                i.activate(dt);
            }
        }else{
            issues.put(id, new Issue(message, dt));
        }
        if( alarms!=null) {
            alarms.startTask(id + ":start");
            alarms.startTaskset(id + ":start");
        }
        
        int cnt = getIssueTriggerCount(id);
        if( cnt < 10 || (cnt<100&&cnt%10==0) || (cnt > 100 && cnt%50==0) ){ // Show warning every time if first 10 times, every 10 times till 100 every 50 times afterwards
            Logger.warn( "Issue raised: "+id+" -> "+message +" ("+cnt+")");
        }

        return true;
    }
    /* **********************************************************************************************************/
    /**
     * Resolve all the errors in a certain group
     * @param groupID The group of which to resolve the issues
     * @param dt The timestamp this occurrec
     * @return The amount of issues resolved
     */
    public int resolveErrorGroup( String groupID, LocalDateTime dt ){
        int resolved=0;
        for( String id : issues.keySet() ){
            if( id.startsWith(groupID) && resolveIssue( id,"",dt ) )
                resolved++;            
        }
        return resolved;
    }
    private boolean resolveIssue( String id, String message, LocalDateTime dt ){
        int cnt=1;

        Issue issue = issues.get(id);
        if( issue == null ){
            return false;
        }
        if( !issue.isActive() ){
            return false;
        }
        if( message.isBlank())
            message = issue.message;

        issue.deActivate(dt);
        if( alarms!=null) {
            alarms.startTask(id + ":stop");
            alarms.startTaskset(id + ":stop");
            alarms.cancelTask(id + ":start");
        }
        Resolved r = resolveds.get(id);

        if( r != null ){
            cnt = r.addInstance( issue.getStart(), Duration.between(issue.getStart(), issue.getEnd()).getSeconds() );
        }else{
            resolveds.put( id, new Resolved(issue.getStart(), Duration.between(issue.getStart(), issue.getEnd()).getSeconds()));
        }

        Logger.warn( "Error Resolved: "+id+" -> "+message+"\t [cnt:"+cnt+"]");

        return true;
    }
    /* Needs to be overridden to be instance specifick */
    public void storeInDatabase( Issue i ){
        Logger.info("Not override'n storeInDatabase");
    }
    public void clearOldOccurences( long seconds ){
        Logger.info("Not override'n clearOldOccurences");
    }

    /* ************************************* R E Q D A T A ******************************************************/
    /**
     * Pose a request to the collector 
     * 
     * @param req The request
     * @param html Whether or not to use html EOL characters
     * @return Response or unknown command
     */
    public String replyToSingleRequest( String req, boolean html ){
        String nl = html?"<br>":"\r\n";

        switch( req ){
            case "list":case "listing":case "print":case "":
                return getIssueOverview(html);
            case "alarms": return this.getAlarmOverview(html?"<br>":"\r\n");
            case "reloadalarms":
                if( alarms ==null)
                    return "No alarms yet";
                return this.alarms.reloadTasks()?"Reload succesful":"Failed to reload, check for syntax issues";
            case "report": return this.getDailyReport( html, false );
            case "resolved": return this.getIssues(false, html);
            case "active": return this.getIssues(true, html);
            case "bug": case "debug":  reportIssue("debug", "Debug issue for testing", true);
                return "Issue Activated";
            case "nobug": reportIssue("debug", "Debug issue for testing", false);
                return "Issue Deactivated";
            case "clear": clearOccurences();
                return "Occurences cleared";
            case "?":
                StringJoiner b = new StringJoiner(nl,"",nl);
                b.add( "<title>:list(ing) -> Get a overview of the possible issues.")
                .add( "<title>:print -> Get an overview of the possible issues.")
                .add( "<title>:alarms -> Get an overview of the possible alarms.")
                .add( "<title>:reloadalarms -> Reload the alarms")
                .add( "<title>:report -> Get the daily report")
                .add( "<title>:resolved -> Get a list of the resolved issues.")
                .add( "<title>:active -> Get a list of the active issues.")
                .add( "<title>:bug -> Activate the debug issue.")
                .add( "<title>:nobug -> Stop the debug issue.")
                .add( "<title>:clear -> Reset the resolved count.");
                return b.toString();
            default:
                return "Unknown command: "+req;
        }
    }
    private String getIssueOverview(boolean html){
		StringBuilder b = new StringBuilder();
		for( Entry<String,Issue> issue : issues.entrySet() ){
            Issue is = issue.getValue();
			b.append(issue.getKey()).append(html?" -> ":"\t").append(is.message).append(is.active?" (active)":"").append(html?"<br>":"\r\n");
		}
		return (html?"<br>":"\r\n") + (b.length()==0?"No issues":b.toString());
    }
    private String getAlarmOverview(String newline){
        if( alarms == null)
            return "No alarms yet";
        return alarms.getTaskSetListing(newline)+newline+alarms.getTaskListing(newline)+newline+alarms.getStatesListing();
    }
    private String getIssues( boolean active, boolean html ){
        String newline = html?"<br>":"\r\n";
        String type = active?"Active":"Resolved";

        if( issues.isEmpty())
            return "No issues yet.";
    
        StringJoiner actives = new StringJoiner(
            newline,    // delimiter
            html?"<b>"+type+" Issues</b><br>":type+" Issues\r\n", // prefix
            newline  //suffix
        );            
        actives.setEmptyValue( "None yet."+newline);
        
        for( Entry<String,Issue> issue : issues.entrySet() ){
            Issue i = issue.getValue();

            Resolved r = resolveds.get(issue.getKey());  
            int cnt = r==null?0:r.getCount();   
            String total;

            if( cnt == 0){
                total = TimeTools.convertPeriodtoString( i.isActive()?i.secondsSinceStart():0,TimeUnit.SECONDS);
            }else{
                total = TimeTools.convertPeriodtoString(r.getTotalTime()+(i.isActive()?i.secondsSinceStart():0),TimeUnit.SECONDS);
            }

            if( active ){
                if( i.isActive() ) {
                    actives.add(i.message+" --> "+(cnt==0?"":cnt+" occurences, ")+"total time "+total);
                }                    
            }else{
                if( !i.isActive() && cnt != 0) {
                    actives.add(i.message+" --> "+cnt+(cnt==1?" occurence":" occurences")+", total time "+total);
                } 
            }
        }
        return actives.toString();
    }
    public void clearOccurences(){

        for( String id : issues.keySet() ){
            Resolved r = resolveds.get(id);
            if( r != null ){
                r.clear();
            }else{
                Logger.warn("Resolved is null for id="+id+" ,maybe just no resolveds yet...");
            }
        }
    }
    public String getDailyReport( boolean html, boolean clear ){

    	StringJoiner join = new StringJoiner(html?"<br>":"\r\n");
        
        return join.add( getIssues(true, html) )
                   .add( getIssues(false, html) )
                   .toString();
    }
    /***********************************************************************************************************/
    /***********************************************************************************************************/
    /***********************************************************************************************************/
    public static class Issue{

        LocalDateTime startTime;
        LocalDateTime endTime;
        private boolean active = false;
        String message;

        public Issue( String message ){
            this.message=message;
            activate( );
        }
        public Issue( String message, LocalDateTime dt ){
            this.message=message;
            activate(dt);
        }
        /* Activate */
        public void activate(){
            activate(LocalDateTime.now(ZoneOffset.UTC));
        }
        public void activate( LocalDateTime dt ){
            active = true;
            startTime  = dt;
        }
        public long secondsSinceStart(){
            return Duration.between(startTime, LocalDateTime.now(ZoneOffset.UTC)).getSeconds();
        }
        /* De Activate */
        public void deActivate( ){
            deActivate(LocalDateTime.now(ZoneOffset.UTC));
        }
        public void deActivate( LocalDateTime dt ){
            active = false;
            endTime  = dt;
        }
        /* Status */
        public boolean isActive(){
            return active;
        }
        public LocalDateTime getStart(){
            return startTime;
        }
        public LocalDateTime getEnd(){
            return endTime;
        }
        public String getMessage(){
            return message;
        }
    }
    /***********************************************************************************************************/
    /***********************************************************************************************************/
    /***********************************************************************************************************/
    public static class Resolved{

        ArrayList<LocalDateTime> occurences = new ArrayList<>();
        ArrayList<Long> durations = new ArrayList<>();
        int count=0;

        public Resolved(LocalDateTime start, long duration){
            addInstance(start, duration);
        }
        public long getTotalTime(){
            long total=0;
            for( Long l : durations )
                total+=l;
            return total;
        }
        public void clear(){
            occurences.clear();
            durations.clear();
            count=0;
        }
        public int getCount(){
            return count;
        }
        public int addInstance( LocalDateTime start, long duration ){
            count++;

            occurences.add(start);
            durations.add(duration);

            if( occurences.size()>15){
                occurences.remove(0);
                durations.remove(0);
            }
            return count;
        }
        public String toString(boolean html){
            StringBuilder b = new StringBuilder();
            String newline = html?"<br>":"\r\n";
            b.append("Occurences: ").append(count).append(newline);
            for( int a=0;a<occurences.size();a++ ){
                b.append(secFormat.format(occurences.get(a))).append(" -> ").append(TimeTools.convertPeriodtoString(durations.get(a), TimeUnit.SECONDS));
            }
            return b.toString();
        }
    }
}