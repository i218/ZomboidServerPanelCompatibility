package io.serverpanelbridge;
import java.util.*;
import java.util.function.BiConsumer;
/** Private in-memory, bounded startup tail; never a second HTTP sender. */
final class PreloadConsole {
    private static ConsoleCapture capture;
    private static final Deque<String[]> tail=new ArrayDeque<>();
    private static BiConsumer<String,String> target;
    private static long dropped;
    private PreloadConsole(){}
    static synchronized void install(){if(capture==null)capture=new ConsoleCapture(PreloadConsole::offer);}
    private static synchronized void offer(String stream,String text){
        if(target!=null){target.accept(stream,text);return;}
        if(capture==null)return;
        if(tail.size()==500){tail.removeFirst();dropped++;}
        tail.addLast(new String[]{stream,text.substring(0,Math.min(text.length(),4000))});
    }
    static synchronized ConsoleCapture claim(BiConsumer<String,String> sink){
        if(capture==null || target!=null)return null;
        target=sink;for(String[] line:tail)sink.accept(line[0],line[1]);tail.clear();
        if(dropped>0)sink.accept("log","Bridge preload buffer discarded "+dropped+" oldest lines");
        ConsoleCapture result=capture;capture=null;return result;
    }
    static void discard(){
        ConsoleCapture old;
        synchronized(PreloadConsole.class){old=capture;capture=null;target=null;tail.clear();dropped=0;}
        // Do not hold the queue lock while waiting for a stream writer.
        if(old!=null)old.close();
    }
    static synchronized boolean installed(){return capture!=null || target!=null;}
}
