package org.eljah.busradio;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.eljah.busradio.Model.*;

/** Promotes a manifest only after every immutable media blob has passed verification. */
public final class Cache {
    private final Api api;private final String busId;private final long maxBytes;
    public final Path root,media;
    private volatile Manifest active;
    public Cache(Path root,Api api,String busId,long maxBytes)throws IOException{
        this.root=root;this.api=api;this.busId=Model.id(busId);this.maxBytes=maxBytes;media=root.resolve("media");Files.createDirectories(media);
        Path f=root.resolve("manifest.json");if(Files.exists(f)){active=Json.read(Files.readString(f),Manifest.class);if(!active.busId().equals(busId))throw new IOException("Cache belongs to another bus");}
    }
    public Manifest active(){return active;}
    public Path file(Asset a){return media.resolve(a.sha256()+".wav");}
    public synchronized Manifest sync()throws Exception{
        Manifest next=(Manifest)Json.convert(api.json("GET","/api/device/manifest",null),Manifest.class);
        if(!next.busId().equals(busId))throw new IOException("Manifest bus mismatch");
        if(active!=null&&next.revision()<active.revision())throw new IOException("Refusing manifest rollback");
        for(Asset a:next.assets())download(a);
        String text=Json.write(next);Path manifest=root.resolve("manifest.json");if(Files.exists(manifest))Support.atomic(root.resolve("manifest.previous.json"),Files.readAllBytes(manifest));
        Support.atomic(manifest,text);active=next;return next;
    }
    private long usage()throws IOException{try(var stream=Files.list(media)){long total=0;for(Path p:stream.toList())if(Files.isRegularFile(p))total=Math.addExact(total,Files.size(p));return total;}}
    private void download(Asset a)throws Exception{
        Path dest=file(a),part=media.resolve(a.sha256()+".part");
        if(Files.exists(dest)&&Files.size(dest)==a.bytes()&&Support.hash(dest).equals(a.sha256())){validateAudio(dest,a);return;}
        if(Files.exists(part)&&Files.size(part)>a.bytes())Files.delete(part);
        if(Files.exists(part)&&Files.size(part)==a.bytes()){
            if(Support.hash(part).equals(a.sha256())){validateAudio(part,a);Support.move(part,dest);return;}Files.delete(part);
        }
        long offset=Files.exists(part)?Files.size(part):0,needed=a.bytes()-offset;
        if(usage()+needed>maxBytes||Files.getFileStore(media).getUsableSpace()<needed+10485760)throw new IOException("Insufficient cache space; current manifest preserved");
        Map<String,String> headers=offset>0?Map.of("Range","bytes="+offset+"-","If-Range","\""+a.sha256()+"\""):Map.of();
        try(Api.Response response=api.request("GET","/api/device/assets/"+a.sha256(),null,headers)){
            int status=response.raw.statusCode();
            if(status==206){String expected="bytes "+offset+"-"+(a.bytes()-1)+"/"+a.bytes();if(!response.raw.headers().firstValue("Content-Range").orElse("").equals(expected))throw new IOException("Incorrect Content-Range");}
            else if(status==200)offset=0;else throw new IOException("Asset HTTP "+status);
            if(!response.raw.headers().firstValue("ETag").orElse("").equals("\""+a.sha256()+"\""))throw new IOException("Asset ETag mismatch");
            try(OutputStream out=Files.newOutputStream(part,StandardOpenOption.CREATE,StandardOpenOption.WRITE,offset==0?StandardOpenOption.TRUNCATE_EXISTING:StandardOpenOption.APPEND)){
                byte[] bytes=new byte[65536];long total=offset;for(int n;(n=response.raw.body().read(bytes))>=0;){total+=n;if(total>a.bytes())throw new IOException("Oversized asset");out.write(bytes,0,n);}
                if(total!=a.bytes())throw new IOException("Incomplete asset; partial file retained");
            }
        }
        if(!Support.hash(part).equals(a.sha256())){Files.deleteIfExists(part);throw new IOException("SHA256 mismatch; previous manifest retained");}
        validateAudio(part,a);try(var ch=java.nio.channels.FileChannel.open(part,StandardOpenOption.WRITE)){ch.force(true);}Support.move(part,dest);
    }
    private static void validateAudio(Path file,Asset a)throws IOException{if(Wave.inspect(file).durationMs()!=a.durationMs())throw new IOException("Audio duration differs from manifest");}
}
