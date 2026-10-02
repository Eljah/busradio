package io.github.eljah.busradio.server;
import io.github.eljah.busradio.core.*;
import static io.github.eljah.busradio.core.Model.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class AudioImporter {
 private AudioImporter(){}
 public static Asset upload(Store store,InputStream body,String name,String kind,String rights,String ffmpeg)throws Exception{
  if(name==null||name.isBlank()||name.length()>200||rights==null||rights.isBlank()||rights.length()>2000||!Set.of("MUSIC","AD","FILLER").contains(kind))throw new IllegalArgumentException("Name, valid kind and rights note required");
  Path input=Files.createTempFile(store.root,"upload-",".audio"),pcm=Files.createTempFile(store.root,"transcode-",".pcm"),log=Files.createTempFile(store.root,"ffmpeg-",".log");Process process=null;
  try{
   try(OutputStream out=Files.newOutputStream(input)){byte[] b=new byte[65536];long total=0;int n;while((n=body.read(b))>=0){total+=n;if(total>256L*1024*1024)throw new IllegalArgumentException("Upload exceeds 256 MiB");out.write(b,0,n);}}
   process=new ProcessBuilder(ffmpeg,"-nostdin","-hide_banner","-loglevel","error","-y","-protocol_whitelist","file,pipe","-i",input.toString(),"-vn","-t","3600","-ac","1","-ar",Integer.toString(SAMPLE_RATE),"-acodec","pcm_s16le","-f","s16le",pcm.toString()).redirectError(log.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
   if(!process.waitFor(120,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Audio conversion timed out");}
   if(process.exitValue()!=0)throw new IllegalArgumentException("Audio decoder rejected the upload");
   String hash=FilesEx.sha(pcm);Asset a=new Asset(UUID.randomUUID().toString(),name,hash,Files.size(pcm),kind,rights);
   Path target=store.media.resolve(hash+".pcm");if(!Files.exists(target)){try(var f=java.nio.channels.FileChannel.open(pcm,StandardOpenOption.WRITE)){f.force(true);}Files.move(pcm,target,StandardCopyOption.ATOMIC_MOVE);}store.put("assets",a.id(),a);return a;
  }finally{if(process!=null&&process.isAlive())process.destroyForcibly();Files.deleteIfExists(input);Files.deleteIfExists(pcm);Files.deleteIfExists(log);}
 }
}
