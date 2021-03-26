package util.database;

import java.time.Instant;
import java.util.StringJoiner;

import util.tools.TimeTools;

public class Insert {
    
    private StringJoiner join=null;

    public Insert( String tablename ){
        join = new StringJoiner(",","INSERT INTO "+tablename+" VALUES (",");");
    }
    public static Insert into(String table){
        return new Insert(table);
    }
    public Insert addText( String value ){
        join.add("'"+value+"'");
        return this;
    }
    public Insert addNumber( double nr ){
        join.add( ""+nr );
        return this;
    }
    public Insert addNr( double nr ){        
        return addNumber(nr);
    }
    public Insert addNr( int nr ){
        join.add( ""+nr );
        return this;
    }
    public Insert addNumber( int nr ){        
        return addNr(nr);
    }
    public Insert addNumbers( int... nrs ){
        for( int nr:nrs)
            join.add(""+nr);
        return this;    
    }
    public Insert addNumbers( double... nrs ){
        for( double nr:nrs)
            join.add(""+nr);
        return this;    
    }
    public Insert addTimestamp(){
        return addText(TimeTools.formatLongUTCNow());
    }
    public Insert addEpochMillis(){
        return addNumber( Instant.now().toEpochMilli() );
    }
    public String toString(){
        return join.toString();
    }
    public String create(){
        return join.toString();
    }    
}