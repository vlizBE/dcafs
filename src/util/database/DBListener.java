package util.database;

public interface DBListener {
    void notifyFull( Database db );    // Ntify that the buffer has reached the maximum (set) capacity
    void notifyRollOverAdded( SQLiteDB db );    // Notify that the rollover setting of the database was changed
}