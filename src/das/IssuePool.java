package das;

import io.Writable;
import org.tinylog.Logger;
import org.w3c.dom.Element;
import util.tools.TimeTools;
import util.xml.XMLfab;
import util.xml.XMLtools;
import worker.Datagram;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class IssuePool implements Commandable{

    private HashMap<String,Issue> issues = new HashMap<>();
    private BlockingQueue<Datagram> dQueue;
    private Path settingsPath;

    public IssuePool( BlockingQueue<Datagram> dQueue, Path settingsPath){
        this.dQueue=dQueue;
        this.settingsPath=settingsPath;
        readFromXML();
    }

    /**
     * Read the issues from the settings.xml,
     * @return True if any were read
     */
    public boolean readFromXML( ){
        XMLfab.getRootChildren(settingsPath,"dcafs","settings","issues","*").forEach(
                issueEle ->
                {
                    String id = XMLtools.getStringAttribute(issueEle,"id","");
                    var issue = new Issue(XMLtools.getChildValueByTag(issueEle,"message",""));
                    for( Element cmd : XMLtools.getChildElements(issueEle,"cmd")){
                        switch( cmd.getAttribute("when") ){
                            case "start": issue.atStart(cmd.getTextContent()); break;
                            case "stop": issue.atStop(cmd.getTextContent()); break;
                            default:
                                Logger.error("Unknown when used: "+cmd.getAttribute("when"));
                                continue;
                        }
                    }
                    issues.put(id,issue);
                }
        );
        return !issues.isEmpty();
    }
    @Override
    public String replyToCommand(String[] request, Writable wr, boolean html) {
        var cmds = request[1].split(",");
        String nl = html?"<br>":"\r\n";
        Issue issue;
        StringJoiner join;
        switch( cmds[0] ){
            case "?":
                join = new StringJoiner(nl);
                join.add("issue:? -> Show this message")
                        .add("issue:addblank -> Add a blank issue node with some example issues")
                        .add("issue:start -> Start an issue")
                        .add("issue:stop -> Stop an active issue")
                        .add("issue:add,id,message -> Add a new issue")
                        .add("issue:listactive -> Return a list of active issues")
                        .add("issue:listresolved -> Return a list of currently inactive issues")
                        .add("issue:resetall -> Reset the issue counters")
                        .add("issue:listall -> List all id/message pairs of the issues");
                return join.toString();
            case "add":
                if( cmds.length!=3)
                    return "Not enough parameters: issue:add,issueid,message";
                addIssue(cmds[1],cmds[2]);
                XMLfab.withRoot(settingsPath,"dcafs","settings").digRoot("issues")
                        .addParent("issue").attr("id",cmds[1]).content(cmds[2]).build();
                return "Issue added";
            case "addblank":
                XMLfab.withRoot(settingsPath,"dcafs","settings").digRoot("issues")
                        .addParent("issue","Issue without commands").attr("id","issueid")
                        .content("Message/info on the issue")
                        .addParent("issue","Issue with commands").attr("id","issue2")
                        .addChild("message","Message/info on the issue")
                        .addChild("cmd","cmd to run on start").attr("when","start")
                        .addChild("cmd","cmd to run on stop").attr("when","stop").build();
                return "Blank added to xml";
            case "trip":
                if( cmds.length!=2)
                    return "Not enough parameters: issue:trip,issueid";
                issue = issues.get(cmds[1]);
                if( issue!=null) {
                    issue.increment();
                    return "Increased count of "+cmds[1];
                }
                return "No such issue: "+cmds[1];
            case "start":
                if( cmds.length!=2)
                    return "Not enough parameters: issue:start,issueid";
                issue = issues.get(cmds[1]);
                if( issue!=null) {
                    issue.start();
                    return "Issue started "+cmds[1];
                }
                return "No such issue: "+cmds[1];
            case "stop":
                if( cmds.length!=2)
                    return "Not enough parameters: issue:stop,issueid";
                issue = issues.get(cmds[1]);
                if( issue!=null){
                    issue.stop();
                    return "Issue stopped "+cmds[1];
                }
                return "No such issue: "+cmds[1];
            case "resetall":
                issues.values().forEach( is -> is.clear());
                return "Issues reset";
            case "listactive":
                join = new StringJoiner(nl,html?"<b>Active Issues</b><br>":"Active Issues\r\n","");
                join.setEmptyValue("None yet.");
                issues.values().stream().filter( is -> is.active)
                        .forEach( is->join.add(is.message+" --> total time "+ TimeTools.convertPeriodtoString(is.getTotalActiveTime(), TimeUnit.SECONDS)));
                return join.toString();
            case "listresolved":
                join = new StringJoiner(nl,(html?"<b>Resolved Issues</b><br>":"Resolved Issues\r\n"),"");
                join.setEmptyValue("None Yet");
                issues.values().stream().filter( is -> !is.active && is.totalCycles!=0 )
                        .forEach( is->join.add(is.message+" --> "
                                + (is.totalCycles==1?"once, ":is.totalCycles+" occurrences, ")+"total time "
                                + TimeTools.convertPeriodtoString(is.totalActiveTime, TimeUnit.SECONDS)));
                return join.toString();
            case "listall":
                join = new StringJoiner(nl,(html?"<b>Issues</b><br>":"Issues\r\n"),"");
                join.setEmptyValue("None Yet");
                issues.forEach( (key,val)->join.add(key+" --> "+val.getMessage()) );
                return join.toString();
        }
        return "unknown command: "+request[0]+":"+request[1];
    }
    public void addIfNewAndStart( String id, String message){
        var is = issues.get(id);
        if( is==null)
            addIssue(id,message);
        issues.get(id).start();
    }
    public void addIfNewAndStop( String id, String message){
        var is = issues.get(id);
        if( is==null) {
            addIssue(id, message);
        }else{
            is.stop();
        }
    }
    public void addIfNewAndIncrement( String id, String message){
        var is = issues.get(id);
        if( is==null)
            addIssue(id,message);
        issues.get(id).increment();
    }
    public int getIssueTriggerCount(String id){
        var is = issues.get(id);
        if( is!=null)
            return is.totalCycles;
        return 0;
    }
    @Override
    public boolean removeWritable(Writable wr) {
        return false;
    }
    public void addIssue(String id, String message){
        issues.put(id,new Issue(message));
    }
    public boolean isActive( String id ){
        var is = issues.get(id);
        if( is!=null)
            return is.active;
        return false;
    }
    public ArrayList<String> getActives(){
        var list = new ArrayList<String>();
        issues.keySet().forEach(list::add);
        return list;
    }
    public class Issue{

        LocalDateTime lastStartTime;
        LocalDateTime lastEndTime;
        long totalActiveTime=0;
        int totalCycles=0;
        private boolean active = false;
        String message;

        ArrayList<String> startCmds;
        ArrayList<String> stopCmds;

        /* Creation */
        public Issue( String message ){
            this.message=message;
        }

        public Issue atStart( String cmd){
            if( cmd==null)
                return this;
            if( startCmds==null)
                startCmds = new ArrayList<>();
            if( !startCmds.contains(cmd))
                startCmds.add(cmd);
            return this;
        }
        public Issue atStop( String cmd){
            if( cmd==null)
                return this;
            if( stopCmds==null)
                stopCmds = new ArrayList<>();
            if( !stopCmds.contains(cmd))
                stopCmds.add(cmd);
            return this;
        }

        /* Usage */
        public void start(){
            start(LocalDateTime.now(ZoneOffset.UTC));
        }
        public boolean start( LocalDateTime dt ){
            if( active )
                return false;
            active = true;
            if( totalCycles < 10 || (totalCycles<100&&totalCycles%10==0) || (totalCycles > 100 && totalCycles%50==0) ){ // Show warning every time if first 10 times, every 10 times till 100 every 50 times afterwards
                Logger.warn( "Issue raised: "+message +" ("+totalCycles+")");
            }
            lastStartTime = dt;
            lastEndTime=null;
            startCmds.forEach( c -> dQueue.add(Datagram.system(c)));
            return true;
        }
        public void increment(){
            lastStartTime=LocalDateTime.now(ZoneOffset.UTC);
            totalCycles++;
        }
        public long secondsSinceStart(){
            return Duration.between(lastStartTime, LocalDateTime.now(ZoneOffset.UTC)).getSeconds();
        }
        public long getTotalActiveTime(){
            return totalActiveTime+(active?secondsSinceStart():0);
        }
        /* De Activate */
        public void stop( ){
            stop(LocalDateTime.now(ZoneOffset.UTC));
        }
        public boolean stop( LocalDateTime dt ){
            if( !active )
                return false;
            active = false;
            totalCycles++;
            lastEndTime = dt;
            totalActiveTime += Duration.between(lastStartTime, lastEndTime).getSeconds();
            stopCmds.forEach( c -> dQueue.add(Datagram.system(c)));
            return true;
        }
        public void clear(){
            totalCycles=0;
            totalActiveTime=0;
        }
        /* Status */
        public boolean isActive(){
            return active;
        }
        public LocalDateTime getStart(){
            return lastStartTime;
        }
        public LocalDateTime getEnd(){
            return lastEndTime;
        }
        public String getMessage(){
            return message;
        }
    }
}
