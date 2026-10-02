package org.eljah.busradio;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.eljah.busradio.Model.*;

/** Single-server pilot store. All configuration changes are copy-on-write snapshots. */
public final class Store implements AutoCloseable {
    private final Support.InstanceLock instanceLock;
    public final Path root,media;
    private Map<String,Object> state;
    public Store(Path root)throws IOException{
        this.root=root;Files.createDirectories(root);instanceLock=new Support.InstanceLock(root.resolve("server.lock"));media=root.resolve("media");Files.createDirectories(media);Files.createDirectories(root.resolve("events"));Files.createDirectories(root.resolve("heartbeat"));Files.createDirectories(root.resolve("tmp"));
        Path f=root.resolve("state.json");
        if(Files.exists(f))state=Json.object(Json.parse(Files.readString(f)));
        else{state=new LinkedHashMap<>();state.put("revision",0L);for(String k:List.of("assets","playlists","campaigns","routes","devices"))state.put(k,new LinkedHashMap<>());save(state);}
    }
    private void save(Map<String,Object> copy)throws IOException{Support.atomic(root.resolve("state.json"),Json.write(copy));}
    public synchronized String snapshot(){return Json.write(state);}
    private Map<String,Object> collection(String name){return Json.object(state.get(name));}
    private <T>T get(String collection,String id,Class<T> type){Object o=collection(collection).get(id);if(o==null)throw new IllegalArgumentException("Unknown "+collection+": "+id);return type.cast(Json.convert(o,type));}
    private synchronized void put(String collection,String key,Object value)throws IOException{
        Map<String,Object> copy=Json.object(Json.parse(Json.write(state)));Json.object(copy.get(collection)).put(key,Json.parse(Json.write(value)));copy.put("revision",((Number)state.get("revision")).longValue()+1);save(copy);state=copy;
    }
    public synchronized Asset upload(Path normalized,String name,String kind)throws IOException{
        Wave.Info info=Wave.inspect(normalized);String hash=Support.hash(normalized);
        Asset a=new Asset(UUID.randomUUID().toString(),name,hash,Files.size(normalized),info.durationMs(),kind);
        Path dest=media.resolve(hash+".wav");if(!Files.exists(dest))Support.move(normalized,dest);put("assets",a.id(),a);return a;
    }
    public synchronized void playlist(Playlist p)throws IOException{for(String id:p.tracks())if(!get("assets",id,Asset.class).kind().equals("MUSIC"))throw new IllegalArgumentException("Playlist requires MUSIC assets");put("playlists",p.id(),p);}
    public synchronized void campaign(Campaign c)throws IOException{if(!get("assets",c.assetId(),Asset.class).kind().equals("AD"))throw new IllegalArgumentException("Campaign requires AD asset");put("campaigns",c.id(),c);}
    public synchronized void route(Route r)throws IOException{put("routes",r.key(),r);}
    public synchronized void importRoutes(List<Route> routes)throws IOException{
        if(routes.isEmpty())throw new IllegalArgumentException("No routes found");
        Map<String,Object> copy=Json.object(Json.parse(Json.write(state)));Map<String,Object> dest=Json.object(copy.get("routes"));for(Route r:routes)dest.put(r.key(),Json.parse(Json.write(r)));copy.put("revision",((Number)state.get("revision")).longValue()+1);save(copy);state=copy;
    }
    public synchronized void assignment(Assignment d)throws IOException{get("playlists",d.playlistId(),Playlist.class);get("routes",d.routeId()+"~"+d.direction(),Route.class);put("devices",d.id(),d);}
    public synchronized void reassign(String id,String playlistId,String routeId,int direction)throws IOException{Assignment old=get("devices",id,Assignment.class);assignment(new Assignment(id,playlistId,routeId,direction,old.tokenHash()));}
    public void close()throws IOException{instanceLock.close();}
    public synchronized boolean authenticate(String busId,String token){try{Assignment d=get("devices",Model.id(busId),Assignment.class);return Support.same(d.tokenHash(),Support.hash(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(RuntimeException e){return false;}}
    public synchronized Manifest manifest(String busId){
        Assignment d=get("devices",busId,Assignment.class);Playlist p=get("playlists",d.playlistId(),Playlist.class);
        List<Campaign> campaigns=collection("campaigns").values().stream().map(o->(Campaign)Json.convert(o,Campaign.class)).filter(c->c.routeId().isEmpty()||c.routeId().equals(d.routeId())).toList();
        Set<String> ids=new LinkedHashSet<>(p.tracks());campaigns.forEach(c->ids.add(c.assetId()));List<Asset> assets=ids.stream().map(id->get("assets",id,Asset.class)).toList();
        return new Manifest(1,((Number)state.get("revision")).longValue(),busId,d.routeId(),d.direction(),p,assets,campaigns,collection("routes").values().stream().map(o->(Route)Json.convert(o,Route.class)).filter(r->r.id().equals(d.routeId())).toList());
    }
    public synchronized List<String> events(String busId,List<Event> events)throws IOException{
        if(events.size()>100)throw new IllegalArgumentException("Max 100 events per request");List<String> accepted=new ArrayList<>();
        for(Event e:events){if(!e.busId().equals(busId))throw new IllegalArgumentException("Bus identity mismatch");Path f=root.resolve("events").resolve(busId+"_"+e.id()+".json");String json=Json.write(e);
            if(Files.exists(f)){if(!Files.readString(f).equals(json))throw new IllegalArgumentException("Event ID collision");}
            else Support.atomic(f,json);accepted.add(e.id());
        }return accepted;
    }
    public synchronized void heartbeat(String id,Object data)throws IOException{Support.atomic(root.resolve("heartbeat").resolve(Model.id(id)+".json"),Json.write(Map.of("busId",id,"receivedAt",Instant.now(),"node",data)));}
    public List<Object> recent(String dir,int max)throws IOException{
        List<Path> files;try(var paths=Files.list(root.resolve(dir))){files=paths.filter(p->p.toString().endsWith(".json")).sorted(Comparator.comparingLong((Path p)->p.toFile().lastModified()).reversed()).limit(max).toList();}
        List<Object> result=new ArrayList<>();for(Path p:files)result.add(Json.parse(Files.readString(p)));return result;
    }
}
