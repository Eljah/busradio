package org.eljah.busradio;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.eljah.busradio.Model.Event;

public final class Outbox {
    private final Path root;
    public Outbox(Path root)throws IOException{this.root=root;Files.createDirectories(root);}
    public synchronized void add(Event e)throws IOException{
        try(var list=Files.list(root)){if(list.filter(p->p.toString().endsWith(".json")).limit(20000).count()>=20000)throw new IOException("Playback outbox full");}
        Support.atomic(root.resolve(e.id()+".json"),Json.write(e));
    }
    public synchronized List<Event> pending(int max)throws IOException{
        List<Path> files;try(var stream=Files.list(root)){files=stream.filter(p->p.toString().endsWith(".json")).sorted().limit(max).toList();}
        List<Event> events=new ArrayList<>();for(Path p:files)events.add(Json.read(Files.readString(p),Event.class));return events;
    }
    public void flush(Api api)throws Exception{
        List<Event> events=pending(100);if(events.isEmpty())return;Map<String,Object> result=Json.object(api.json("POST","/api/device/events",events));
        Set<String> sent=new HashSet<>();events.forEach(e->sent.add(e.id()));
        for(Object id:Json.array(result.get("accepted"))){if(!sent.contains(id.toString()))throw new IOException("Unexpected event acknowledgment");Files.deleteIfExists(root.resolve(id+".json"));}
    }
}
