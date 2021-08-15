package util.taskblocks;

import util.data.NumericVal;

import java.util.ArrayList;
import java.util.Optional;

public interface TaskBlock {

    /* Shared Numerical Mem */
    void setSharedMem( ArrayList<NumericVal> mem);
    boolean hasSharedMem();
    ArrayList<NumericVal> getSharedMem();

    Optional<TaskBlock> build(TaskBlock prev, String set);
    void addNext(TaskBlock block);



    boolean start();

    void nextOk();
    void nextFailed();

    Optional<TaskBlock> getSourceBlock();
}
