package io.github.eljah.busradio.edge;
import static io.github.eljah.busradio.core.Model.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public final class AudioSinks {
 private AudioSinks(){}
 public interface Sink extends AutoCloseable {void write(byte[] bytes,int length)throws IOException;void close()throws IOException;}
 public static byte[] header(long bytes){ByteBuffer b=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int)Math.min(0xffffffffL,bytes+36)).put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1).putInt(SAMPLE_RATE).putInt(BYTES_PER_SECOND).putShort((short)2).putShort((short)16).put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int)bytes);return b.array();}
 public static Sink open(Config c)throws Exception{return switch(c.get("audio.mode","null")){
  case "null"->new Sink(){public void write(byte[] b,int n){}public void close(){}};
  case "wav"->new Wav(Path.of(c.get("audio.file","var/aircheck.wav")));
  case "alsa"->new ProcessSink(List.of(c.get("alsa.executable","/usr/bin/aplay"),"-q","-t","raw","-f","S16_LE","-r",Integer.toString(SAMPLE_RATE),"-c","1"),false);
  case "fm"->{
   if(!c.bool("rf.enabled",false)||c.get("rf.authorization","").isBlank())throw new IllegalStateException("RF disabled: explicit authorization reference required");
   String model=Files.readString(Path.of("/proc/device-tree/model")).replace("\u0000","");
   if(!model.matches(".*Raspberry Pi (3|4|Zero 2).*"))throw new IllegalStateException("Unsupported RF hardware: "+model+". Pi 5 / RP1 is not supported.");
   if(!c.bool("audio.realtime",true))throw new IllegalStateException("RF requires real-time PCM pacing");
   double mhz=Double.parseDouble(c.get("rf.frequencyMHz",""));if(!Double.isFinite(mhz)||mhz<87.5||mhz>108)throw new IllegalArgumentException("FM frequency must be explicitly set in the 87.5..108 MHz band; this is not a legal authorization");
   String executable=c.get("rf.executable","/usr/local/bin/fm_transmitter");Path file=Path.of(executable);if(!file.isAbsolute()||!Files.isExecutable(file))throw new IllegalStateException("Native FM executable missing");
   yield new ProcessSink(List.of(executable,"-f",Double.toString(mhz),"-"),true);
  }
  default->throw new IllegalArgumentException("Unknown audio mode");};}
 public static final class Wav implements Sink {
  private final RandomAccessFile file;private long bytes;
  public Wav(Path path)throws IOException{Files.createDirectories(path.toAbsolutePath().getParent());file=new RandomAccessFile(path.toFile(),"rw");file.setLength(0);file.write(header(0));}
  public void write(byte[] b,int n)throws IOException{if(bytes+n>0xfffffff0L-36)throw new IOException("WAV rollover required");file.write(b,0,n);bytes+=n;}
  public void close()throws IOException{file.seek(0);file.write(header(bytes));file.getFD().sync();file.close();}
 }
 /** One continuous WAV stream. Starting a transmitter per song would drop the carrier at every boundary. */
 public static final class ProcessSink implements Sink {
  private final Process process;private final OutputStream pipe;private long bytes;
  private final ScheduledExecutorService timeout=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"audio-watchdog");t.setDaemon(true);return t;});
  private final Thread exitHook;

  public ProcessSink(List<String> command,boolean wav)throws IOException{process=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start();exitHook=new Thread(()->{process.destroy();try{if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();process.destroyForcibly();}},"stop-native-audio");Runtime.getRuntime().addShutdownHook(exitHook);pipe=new BufferedOutputStream(process.getOutputStream(),4096);if(wav){pipe.write(header(0xffffffffL));pipe.flush();}}
  public void write(byte[] b,int n)throws IOException{if(!process.isAlive())throw new IOException("Audio process exited: "+process.exitValue());if((bytes+=n)>BYTES_PER_SECOND*12L*3600)throw new IOException("Restart audio backend after 12 hours");ScheduledFuture<?> watchdog=timeout.schedule(process::destroyForcibly,5,TimeUnit.SECONDS);try{pipe.write(b,0,n);pipe.flush();}finally{watchdog.cancel(false);}}
  public void close()throws IOException{ScheduledFuture<?> watchdog=timeout.schedule(process::destroyForcibly,3,TimeUnit.SECONDS);try{pipe.close();}finally{watchdog.cancel(false);timeout.shutdownNow();try{Runtime.getRuntime().removeShutdownHook(exitHook);}catch(IllegalStateException ignored){}try{if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}}catch(InterruptedException e){Thread.currentThread().interrupt();process.destroyForcibly();}}}
 }
}
