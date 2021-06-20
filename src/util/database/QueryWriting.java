package util.database;

import org.influxdb.dto.Point;

import java.util.concurrent.ConcurrentMap;

public interface QueryWriting {

    int doDirectInsert(String id,String table, Object... values);
    boolean buildInsert(String id,String table, ConcurrentMap<String, Double> rtvals,
                        ConcurrentMap<String, String> rttext, String macro);
    boolean addQuery(String id, String query);
    boolean writeInfluxPoint( String id, Point p);
}
