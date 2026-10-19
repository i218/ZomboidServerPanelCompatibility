package io.serverpanelbridge;
import java.io.*;
import java.nio.charset.Charset;
import java.util.function.BiConsumer;

/** Optional tee: preserves existing streams and bounds incomplete lines to 16 KB. */
final class ConsoleCapture implements AutoCloseable {
    private final PrintStream originalOut=System.out,originalErr=System.err;
    private final Tap out,err;
    private final PrintStream replacementOut,replacementErr;
    ConsoleCapture(BiConsumer<String,String> sink) {
        out=new Tap(originalOut,"stdout",sink);err=new Tap(originalErr,"stderr",sink);
        replacementOut=new PrintStream(out,true,out.charset);replacementErr=new PrintStream(err,true,err.charset);
        System.setOut(replacementOut);System.setErr(replacementErr);
    }
    private static final class Tap extends OutputStream {
        final PrintStream original;final String stream;final BiConsumer<String,String> sink;final Charset charset;
        final ByteArrayOutputStream line=new ByteArrayOutputStream();boolean carriage;
        Tap(PrintStream original,String stream,BiConsumer<String,String> sink){
            this.original=original;this.stream=stream;this.sink=sink;
            Charset detected=Charset.defaultCharset();
            try{detected=(Charset)PrintStream.class.getMethod("charset").invoke(original);}catch(ReflectiveOperationException ignored){}
            charset=detected;
        }
        public synchronized void write(int value){byte[] one={(byte)value};write(one,0,1);}
        public synchronized void write(byte[] bytes,int offset,int count){
            original.write(bytes,offset,count);
            for(int i=offset;i<offset+count;i++) {
                int value=bytes[i]&255;
                if(value==10){if(!carriage)emit();carriage=false;}
                else if(value==13){emit();carriage=true;}
                else {carriage=false;if(line.size()<16000)line.write(value);}
            }
        }
        private void emit(){String value=new String(line.toByteArray(),charset);line.reset();sink.accept(stream,value);}
        synchronized void finish(){if(line.size()>0)emit();}
        public void flush(){original.flush();}
        public void close(){flush();} // Never close the game's original stream.
    }
    public void close(){
        if(System.out==replacementOut)System.setOut(originalOut);
        if(System.err==replacementErr)System.setErr(originalErr);
        out.finish();err.finish();
    }
}
