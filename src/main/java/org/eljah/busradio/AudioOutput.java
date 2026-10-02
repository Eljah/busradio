package org.eljah.busradio;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.eljah.busradio.Support.Config;

/** A continuous WAV/PCM stream; never starts a transmitter once per song. */
public final class AudioOutput implements AutoCloseable {
    private final Config config;private final Path captures;private OutputStream out;private Path current;private volatile Process radio;private long bytes;
    public AudioOutput(Config c,Path root)throws IOException{config=c;captures=root.resolve("captures");Files.createDirectories(captures);}
    public void write(byte[] b,int count)throws Exception{
        if(out==null)open();out.write(b,0,count);bytes+=count;
        if(radio!=null&&!radio.isAlive())throw new IOException("PiFmRds exited: "+radio.exitValue());
        // Rotate well before RIFF's 4 GiB limit. In RF mode this causes a brief hourly restart.
        long limit=radio==null?64L*1024*1024:44100L*4*3600;
        if(bytes>=limit)close();
    }
    private void open()throws Exception{
        String mode=config.get("audio.mode","wav");bytes=0;
        switch(mode){
            case "discard"->out=OutputStream.nullOutputStream();
            case "wav"->{current=captures.resolve("capture-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".wav");out=new BufferedOutputStream(Files.newOutputStream(current));Wave.header(out,0);}
            case "pifmrds"->{
                if(!config.bool("rf.enabled",false)||!config.get("rf.authorization","").equals("VERIFIED_BY_OPERATOR"))throw new IllegalStateException("RF disabled: explicit authorization and filtering verification required");
                String model=Files.readString(Path.of("/proc/device-tree/model")).replace("\u0000","");
                if(!(model.startsWith("Raspberry Pi 3")||model.startsWith("Raspberry Pi 4")||model.startsWith("Raspberry Pi Zero 2")))throw new IllegalStateException("Unsupported RF board: "+model);
                double frequency=Double.parseDouble(config.required("rf.frequency"));if(!Double.isFinite(frequency)||frequency<87.5||frequency>108)throw new IllegalArgumentException("Invalid FM frequency");
                if(Double.parseDouble(config.get("playback.speed","1"))!=1)throw new IllegalArgumentException("RF playback must use realtime speed");
                Path executable=config.path("rf.executable","/usr/local/bin/pi_fm_rds").toRealPath();
                List<String> command=new ArrayList<>();
                if(config.bool("rf.useSudoHelper",false)){
                    if(!executable.equals(Path.of("/usr/local/sbin/busradio-fm-wrapper")))throw new IllegalArgumentException("Only the fixed root-owned RF helper may be used with sudo");
                    command.addAll(List.of("/usr/bin/sudo","-n","--"));
                }
                command.addAll(List.of(executable.toString(),"-freq",String.format(Locale.ROOT,"%.3f",frequency),"-audio","-","-ps","BUSRADIO","-rt","BusRadio onboard audio"));
                radio=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start();
                out=new BufferedOutputStream(radio.getOutputStream(),8192);Wave.header(out,0x7fff0000L);out.flush();
            }
            default->throw new IllegalArgumentException("Unknown audio.mode");
        }
    }
    public void flush()throws IOException{if(out!=null)out.flush();}
    public void close()throws IOException{
        IOException failure=null;
        if(radio!=null)radio.destroy();
        if(out!=null)try{out.close();}catch(IOException e){failure=e;}
        if(current!=null){
            try(RandomAccessFile f=new RandomAccessFile(current.toFile(),"rw")){f.seek(4);f.writeInt(Integer.reverseBytes((int)(bytes+36)));f.seek(40);f.writeInt(Integer.reverseBytes((int)bytes));}
            try(var stream=Files.list(captures)){List<Path> old=stream.filter(p->p.getFileName().toString().startsWith("capture-")).sorted(Comparator.comparingLong((Path p)->p.toFile().lastModified()).reversed()).toList();for(int i=2;i<old.size();i++)Files.deleteIfExists(old.get(i));}
        }
        if(radio!=null){try{if(!radio.waitFor(2,java.util.concurrent.TimeUnit.SECONDS))radio.destroyForcibly();}catch(InterruptedException e){radio.destroyForcibly();Thread.currentThread().interrupt();}}
        out=null;current=null;radio=null;bytes=0;if(failure!=null)throw failure;
    }
}
