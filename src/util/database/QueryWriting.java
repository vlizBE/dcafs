package util.database;

import util.data.DataProviding;
import org.influxdb.dto.Point;

import java.util.List;
import java.util.Optional;

public interface QueryWriting {

    int doDirectInsert(String id,String table, Object... values);
    boolean buildInsert(String id, String table, DataProviding dp, String macro);
    boolean addQuery(String id, String query);
    Optional<List<List<Object>>> doSelect(String id, String query);
    boolean writeInfluxPoint( String id, Point p);
    boolean hasDB( String id);
    boolean isValid(String id,int timeout);

}
