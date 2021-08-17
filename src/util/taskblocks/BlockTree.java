package util.taskblocks;

import org.tinylog.Logger;

public class BlockTree {
    TaskBlock root;
    TaskBlock last;
    boolean valid =true;

    public BlockTree( TaskBlock origin ){
        root=origin;
        last=origin;
        valid=origin.link(null).build();
    }
    public static BlockTree trunk( TaskBlock origin ){
        return new BlockTree((origin));
    }
    public BlockTree branchOut( TaskBlock branch ){
        if( !valid )
            return this;

        if( branch.link(last).build() ) {
            last = branch;
        }else{
            Logger.error("Build of block failed");
            valid=false;
        }
        return this;
    }
    public BlockTree addTwig( TaskBlock twig ){
        if( !valid )
            return this;

        if( !twig.link(last).build() ) {
            Logger.error("Build of block failed");
            valid=false;
        }
        return this;
    }
    public BlockTree branchIn(){
        if( last.getParent().isPresent() ){
            last = last.getParent().get();
        }else{
            last = root;
        }
        return this;
    }
    public boolean start(){
        return root.start();
    }
    public TaskBlock last(){
        return last;
    }
}
