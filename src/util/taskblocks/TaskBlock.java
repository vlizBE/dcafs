package util.taskblocks;

import util.data.NumericVal;

import java.util.ArrayList;
import java.util.Optional;
import java.util.StringJoiner;

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
    boolean stop();

    void nextOk();
    void nextFailed();
    void getBlockInfo(StringJoiner join,String offset);

    Optional<TaskBlock> getSourceBlock();
}
