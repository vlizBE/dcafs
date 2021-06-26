package util.task;

import org.tinylog.Logger;
import util.task.TaskManager.RUNTYPE;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TaskSet {
		
    private RUNTYPE run = RUNTYPE.ONESHOT;
    private final ArrayList<Task> tasks = new ArrayList<>();
    private String failureTask = null;
    private String id="";
    private String description="";
    int index=0;
    int repeat = -1;
    private boolean active = false;
    boolean interruptable = true;
    String managerID = "???";

    public TaskSet( String id, String description ) {
        this.description=description;
        this.id=id.toLowerCase();
    }

    public TaskSet( String id, String description, RUNTYPE run, int repeats ) {
        this(id,description);
        if( run != null )
            this.run = run;
        if( repeats >0)
            repeats --;
        this.repeat = repeats;

        Logger.tag("TASK").info("["+managerID+"] Created taskset: "+description+" "+id+" "+run+" reps:"+repeats);
    }
    public void setManagerID( String manID ){
        managerID=manID;
    } 
    public static TaskSet createSet( String name, String shortName, RUNTYPE run, int repeats ){
        return new TaskSet(name,shortName,run,repeats);
    }
    public void addTask( Task t ) {
        if( run != RUNTYPE.NOT)
            t.setTaskset( id, tasks.size() );			
        tasks.add(t);
    }
    public List<Task> getTasks(){
        return tasks;
    }
    public Task getTask( int index ) {
        if( index >= tasks.size())
            return null;
        return tasks.get(index);
    }
    public Optional<Task> getTaskByID( String id ){
        return tasks.stream().filter( t-> t.id.equalsIgnoreCase(id)).findFirst();
    }
    public Task getNextTask( int index ) {
        index ++;
        if( index >= tasks.size()) {
            if( repeat == 0) {
                Logger.tag("TASK").info( "["+managerID+"] No repeats left, stopped executing.");
                return null;
            }
            if( repeat > 0 ) {
                Logger.tag("TASK").info( "["+managerID+"] "+description+" has "+repeat+" execution(s) left.");
                repeat --;
            }
            index = 0;
        }
        Logger.tag("TASK").info( "["+managerID+"] Executing next task in the set!\t("+index+") "+tasks.get(index).toString());
        return tasks.get(index);
    }
    public RUNTYPE getRunType(){ return run; }
    public String getDescription(){ return description; }
    public String getID(){ return id;}
    public boolean isActive(){ return active;}
    public boolean isInterruptable(){ return interruptable;}
    public void setFailureTask( String task){
        this.failureTask = task;
    }
    public String getFailureTaskID( ){
        return failureTask;
    }
    public int getTaskCount() {
        return tasks.size();
    }
    public void setStepped() {
        run = RUNTYPE.STEP;
    }
    public void setRepeat( int reps ) {
        this.repeat=reps;
    }
    public int stop( String reason ){
        int a=0;
        int indexCnt=0;
        for( Task task : tasks ){
            if( task.getFuture() != null && !task.getFuture().isDone() ){
                if( task.getFuture().cancel(true) ){
                    a++;
                    Logger.tag("TASK").info("["+managerID+"] Stopped task at index "+indexCnt+ " of set "+description);
                }else{
                    Logger.tag("TASK").info("["+managerID+"] Failed stopping task at index "+indexCnt+ " of set "+description);
                }
            }
            indexCnt++;
        }
        active = false;
        Logger.tag("TASK").info("["+managerID+"] Tried stopping "+description+" (cancelled "+a+" future(s) ) because of "+reason);
        return a;
    }
}