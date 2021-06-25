package com;

import com.Writable;

public interface Readable {

    /**
     * Get the id of the object implementing Writable
     * @return The (preferably unique) id for the implementing object
     */
    String getID();

    /**
     * Add the writable to the list of targets for this readable
     * @param target The writable of the target
     * @return True if added, false if duplicate etc
     */
    boolean addTarget( Writable target);
    /**
     * Indicate if the connection is valid or not. Mainly used to know if the Writable should be removed or not
     * @return True if it's valid
     */
    boolean isInvalid();

}
