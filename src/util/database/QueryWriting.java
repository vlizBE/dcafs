package util.database;

import das.DoubleVal;
import org.influxdb.dto.Point;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;

public interface QueryWriting {

    int doDirectInsert(String id,String table, Object... values);
    boolean buildInsert(String id,String table, ConcurrentMap<String, DoubleVal> rtvals,
                        ConcurrentMap<String, String> rttext, String macro);
    boolean addQuery(String id, String query);
    Optional<List<List<Object>>> doSelect(String id, String query);
    boolean writeInfluxPoint( String id, Point p);
    boolean hasDB( String id);
    boolean isValid(String id,int timeout);

}
