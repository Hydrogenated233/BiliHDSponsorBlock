package top.local.bilihdskip;

/** Captures the core's completed position before fast playback advances past the poll window. */
public final class SeekConfirmation {
    private long generation, target=-1;
    private boolean completed;
    public synchronized long begin(long next){target=next;completed=false;return ++generation;}
    public synchronized long generation(){return generation;}
    public synchronized void completed(long eventGeneration,long position){
        if(eventGeneration==generation&&near(position))completed=true;
    }
    public synchronized boolean reached(long position){return target>=0&&(completed||near(position));}
    public synchronized void clear(){generation++;target=-1;completed=false;}
    private boolean near(long position){return target>=0&&position>=0&&Math.abs(position-target)<=750;}
}
