package util.taskblocks;

import org.tinylog.Logger;

import java.util.Optional;

public class BlockTree {
    AbstractBlock root;
    AbstractBlock last;
    boolean valid =true;

    public BlockTree( AbstractBlock origin ){
        root=origin;
        last=origin;
        valid=origin.link(null).build();
    }
    public static BlockTree trunk( AbstractBlock origin ){
        return new BlockTree((origin));
    }

    public BlockTree branchOut( AbstractBlock branch ){
        if( !valid )
            return this;

        // Check if the same triggerblock already exist on this level, if so use that instead
        if( branch instanceof TriggerBlock || branch instanceof CheckBlock){
            if( getTwigMatch( branch ).map(o->{ last=o; return true; }).orElse(false))
                return this;
        }

        // No matching block found, use the created one
        if( branch.link(last).build() ) {
            last = branch;
        }else{
            Logger.error("Build of block failed: "+branch);
            valid=false;
        }
        return this;
    }
    public Optional<AbstractBlock> getTwigMatch(AbstractBlock block ){
        if( block instanceof TriggerBlock ){
            return last.next.stream()
                    .filter( n -> n instanceof TriggerBlock && ((AbstractBlock)n).ori.equalsIgnoreCase(block.ori))
                    .map( n -> ((AbstractBlock)n))
                    .findFirst();
        }else if( block instanceof CheckBlock ) {
            return last.next.stream()
                    .filter(n -> n instanceof CheckBlock && ((AbstractBlock) n).ori.equalsIgnoreCase(block.ori))
                    .map(n -> ((AbstractBlock) n))
                    .findFirst();
        }
        return Optional.empty();
    }
    public BlockTree addTwig( TaskBlock twig ){
        if( !valid ) {
            Logger.error("Twig not added because invalid: "+twig);
            return this;
        }
        var lastNext = last.getLastNext();
        if( lastNext != null && lastNext instanceof CmdBlock && twig instanceof CmdBlock ){
            CmdBlock a = (CmdBlock) lastNext;
            ((CmdBlock) twig).getCmds().forEach(a::addCmd);
        }else if( !twig.link(last).build() ) {
            Logger.error("Build of block failed: "+twig);
            valid=false;
        }
        return this;
    }
    public BlockTree branchIn(){
        if( last.getParent().isPresent() ){
            last = (AbstractBlock)last.getParent().get();
        }else{
            last = root;
        }
        return this;
    }
    public MetaBlock getMetaBlock(){
        return (MetaBlock) root;
    }
    public boolean start(){
        return root.start(null);
    }
    public TaskBlock last(){
        return last;
    }
}
