package util.taskblocks;

import util.data.NumericVal;

import java.util.ArrayList;
import java.util.Optional;

public interface TaskBlock {

    /* Shared Numerical Mem */
    void setSharedMem( ArrayList<NumericVal> mem);
    boolean hasSharedMem();
    ArrayList<NumericVal> getSharedMem();

    boolean addNext(TaskBlock block);
    Optional<TaskBlock> getParent();
    TaskBlock link( TaskBlock parent);
    boolean addData(String data);
    boolean build();
    boolean start();

    void nextOk();
    void nextFailed();

    Optional<TaskBlock> getSourceBlock();
}
