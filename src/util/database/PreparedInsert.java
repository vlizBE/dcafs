package util.database;

import java.util.ArrayList;
import java.util.StringJoiner;

public class PreparedInsert {

    enum DataType{TEXT,INTEGER,REAL}

    ArrayList<Column> columns = new ArrayList<>();
    ArrayList<Object[]> data = new ArrayList<>();

    String table;
    StringJoiner cols; 
    String statement="";

    public PreparedInsert( String table ){
        this.table=table;
        cols = new StringJoiner(",","INSERT INTO "+table+" ("," ) VALUES (");    
    }
    /* Adding columns */
    public void addTextColumn( String column ){
        columns.add(new Column(column, DataType.TEXT));
        cols.add(column);
    } 
    public void addIntColumn( String column ){
        columns.add(new Column(column, DataType.INTEGER));
        cols.add(column);
    } 
    public void addRealColumn( String column ){
        columns.add(new Column(column, DataType.REAL));
        cols.add(column);
    } 
    public void readyForData(){
        data.clear();

        StringJoiner qMarks = new StringJoiner(",","",");");
        columns.forEach( c -> qMarks.add("?") );
        statement = cols.toString()+columns.toString(); 
        
        data.add( new Object[columns.size()]);
    }

    /* Adding data */
    public void add( String column, Object value ){
        int index = -1;
        for( Column c : columns ){
            index++;
            if( c.id.equalsIgnoreCase(column) ){
                data.get( data.size()-1 )[index]=value;
            }
        }
    } 
    public void add( int index, Object value ){
        if( index < columns.size() )
            data.get(data.size()-1)[index]=value;
    }
    public void next(){
        data.add( new Object[columns.size()]);
    }

    /* Retrieving data */
    public String getStatement( ){
        return statement;
    }  
    public ArrayList<Object[]> getData(){
        return data;
    }

    public static class Column{
        DataType type;
        String id;
        Object defaultValue=null;
        
        public Column( String id, DataType type){
            this.id=id;
            this.type=type;
        }
        public String toString(){
            return id;
        }
    }
}
