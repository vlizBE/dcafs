package com;

public interface Writable {
    /**
     * Write a line that won't have the eol appended
     * @param data The data to write
     * @return True if this was successful
     */
    boolean writeString(String data);

    /**
     * Write a line that has the eol appended
     * @param data The data to write
     * @return True if this was successful
     */
    boolean writeLine(String data);

    /**
     * Write the given data bytes
     * @param data The bytes to write
     * @return True if successful
     */
    boolean writeBytes( byte[] data);
    /**
     * Get the id of the object implementing Writable
     * @return The (preferably unique) id for the implementing object
     */
    String getID();
    /**
     * Indicate if the connection is valid or not. Mainly used to know if the Writable should be removed or not
     * @return True if it's valid
     */
    boolean isConnectionValid();

    Writable getWritable();
}
