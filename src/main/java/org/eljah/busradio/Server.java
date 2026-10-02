package org.eljah.busradio;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.eljah.busradio.Model.*;
import org.eljah.busradio.Support.Config;

public final class Server implements AutoCloseable {
    public final Store store;
    private final HttpServer server;private final ExecutorService executor;
    private final String adminHash,ffmpeg;
    public Server(Config c)throws IOException{
        String token=c.required("admin.token");if(token.length()<32)throw new IllegalArgumentException("Admin token must have at least 32 characters");
        adminHash=Support.hash(token.getBytes(StandardCharsets.UTF_8));ffmpeg=c.get("ffmpeg.path","ffmpeg");store=new Store(c.path("server.data","var/server"));
        server=HttpServer.create(new InetSocketAddress(c.get("server.host","127.0.0.1"),c.integer("server.port",8080)),64);
        executor=new ThreadPoolExecutor(4,24,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64));server.setExecutor(executor);server.createContext("/",this::handle);server.start();
    }
    public int port(){return server.getAddress().getPort();}
    private static void json(HttpExchange x,int code,Object obj)throws IOException{byte[] b=Json.write(obj).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(code,b.length);x.getResponseBody().write(b);}
    private static String body(HttpExchange x,int limit)throws IOException{return new String(Support.limited(x.getRequestBody(),limit),StandardCharsets.UTF_8);}
    private static void method(HttpExchange x,String expected){if(!x.getRequestMethod().equals(expected))throw new IllegalArgumentException("Expected "+expected);}
    private void handle(HttpExchange x)throws IOException{
        x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("Referrer-Policy","no-referrer");
        x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        try{
            String path=x.getRequestURI().getPath();
            if(path.equals("/health")){method(x,"GET");json(x,200,Map.of("status","UP"));return;}
            if(Set.of("/","/app.js","/style.css").contains(path)){method(x,"GET");resource(x,path);return;}
            String auth=x.getRequestHeaders().getFirst("Authorization");String token=auth!=null&&auth.startsWith("Bearer ")?auth.substring(7):"";
            if(path.startsWith("/api/admin/")){
                if(!Support.same(adminHash,Support.hash(token.getBytes(StandardCharsets.UTF_8)))){json(x,401,Map.of("error","Unauthorized"));return;}
                admin(x,path);return;
            }
            if(path.startsWith("/api/device/")){
                String id=x.getRequestHeaders().getFirst("X-Bus-Id");if(id==null||!store.authenticate(id,token)){json(x,401,Map.of("error","Unauthorized device"));return;}
                device(x,path,id);return;
            }
            json(x,404,Map.of("error","Not found"));
        }catch(IllegalArgumentException|NullPointerException e){if(x.getResponseCode()<0)json(x,400,Map.of("error",Objects.toString(e.getMessage(),"Invalid request")));}
        catch(Exception e){System.err.println("HTTP request failed: "+e.getClass().getSimpleName()+": "+e.getMessage());if(x.getResponseCode()<0)json(x,500,Map.of("error","Operation failed; see server log"));}
        finally{x.close();}
    }
    private void resource(HttpExchange x,String path)throws IOException{
        String name=path.equals("/")?"/web/index.html":"/web"+path;
        try(InputStream in=Server.class.getResourceAsStream(name)){
            if(in==null){json(x,404,Map.of("error","Resource missing"));return;}byte[] b=in.readAllBytes();x.getResponseHeaders().set("Content-Type",path.endsWith(".js")?"text/javascript; charset=utf-8":path.endsWith(".css")?"text/css; charset=utf-8":"text/html; charset=utf-8");x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);
        }
    }
    private void admin(HttpExchange x,String path)throws Exception{
        switch(path){
            case "/api/admin/state"->{method(x,"GET");json(x,200,Json.parse(store.snapshot()));}
            case "/api/admin/events"->{method(x,"GET");json(x,200,store.recent("events",200));}
            case "/api/admin/heartbeats"->{method(x,"GET");json(x,200,store.recent("heartbeat",500));}
            case "/api/admin/playlist"->{method(x,"PUT");store.playlist(Json.read(body(x,1048576),Playlist.class));json(x,200,Map.of("ok",true));}
            case "/api/admin/campaign"->{method(x,"PUT");store.campaign(Json.read(body(x,65536),Campaign.class));json(x,200,Map.of("ok",true));}
            case "/api/admin/route"->{method(x,"PUT");store.route(Json.read(body(x,4194304),Route.class));json(x,200,Map.of("ok",true));}
            case "/api/admin/import-buscrawl"->{method(x,"POST");List<Route> routes=Buscrawl.routes(body(x,16777216));store.importRoutes(routes);json(x,200,Map.of("importedDirections",routes.size(),"geometry","stop-chain"));}
            case "/api/admin/assignment"->{method(x,"PUT");var m=Json.object(Json.parse(body(x,65536)));store.reassign(Json.string(m,"id"),Json.string(m,"playlistId"),Json.string(m,"routeId"),(int)Json.number(m,"direction"));json(x,200,Map.of("ok",true));}
            case "/api/admin/device"->{
                method(x,"POST");var m=Json.object(Json.parse(body(x,65536)));String token=Support.token();Assignment a=new Assignment(Json.string(m,"id"),Json.string(m,"playlistId"),Json.string(m,"routeId"),(int)Json.number(m,"direction"),Support.hash(token.getBytes(StandardCharsets.UTF_8)));store.assignment(a);json(x,200,Map.of("id",a.id(),"token",token,"note","Save now. Re-enrolling this ID revokes its old token."));
            }
            case "/api/admin/assets"->{
                method(x,"POST");var params=query(x.getRequestURI().getRawQuery());String name=params.getOrDefault("name","audio"),kind=params.getOrDefault("kind","MUSIC");
                if(!Set.of("MUSIC","AD").contains(kind))throw new IllegalArgumentException("Invalid asset kind");
                Path tmp=Files.createTempFile(store.root.resolve("tmp"),"upload-",".bin"),out=Files.createTempFile(store.root.resolve("tmp"),"audio-",".wav");
                try{try(OutputStream dest=Files.newOutputStream(tmp)){byte[] b=new byte[65536];long size=0;for(int n;(n=x.getRequestBody().read(b))>=0;){size+=n;if(size>268435456)throw new IllegalArgumentException("Max upload: 256 MiB");dest.write(b,0,n);}}
                    Wave.normalize(tmp,out,ffmpeg);Asset asset=store.upload(out,name,kind);json(x,201,asset);
                }finally{Files.deleteIfExists(tmp);Files.deleteIfExists(out);}
            }
            default->json(x,404,Map.of("error","Not found"));
        }
    }
    private void device(HttpExchange x,String path,String busId)throws Exception{
        if(path.equals("/api/device/manifest")){
            method(x,"GET");Manifest m=store.manifest(busId);String tag="\""+m.revision()+":"+busId+"\"";x.getResponseHeaders().set("ETag",tag);
            if(tag.equals(x.getRequestHeaders().getFirst("If-None-Match")))x.sendResponseHeaders(304,-1);else json(x,200,m);return;
        }
        if(path.startsWith("/api/device/assets/")){
            method(x,"GET");String sha=path.substring("/api/device/assets/".length());if(!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid asset hash");
            if(store.manifest(busId).assets().stream().noneMatch(a->a.sha256().equals(sha))){json(x,404,Map.of("error","Not assigned"));return;}
            serveAudio(x,store.media.resolve(sha+".wav"),sha);return;
        }
        if(path.equals("/api/device/events")){
            method(x,"POST");List<Event> events=Json.array(Json.parse(body(x,1048576))).stream().map(o->(Event)Json.convert(o,Event.class)).toList();json(x,200,Map.of("accepted",store.events(busId,events)));return;
        }
        if(path.equals("/api/device/heartbeat")){method(x,"POST");store.heartbeat(busId,Json.parse(body(x,16384)));json(x,200,Map.of("ok",true));return;}
        json(x,404,Map.of("error","Not found"));
    }
    private static void serveAudio(HttpExchange x,Path file,String sha)throws IOException{
        long size=Files.size(file),start=0,end=size-1;boolean partial=false;
        String range=x.getRequestHeaders().getFirst("Range"),ifRange=x.getRequestHeaders().getFirst("If-Range"),etag="\""+sha+"\"";
        if(range!=null&&(ifRange==null||ifRange.equals(etag))){
            Matcher m=Pattern.compile("bytes=(\\d+)-(\\d*)").matcher(range);
            if(!m.matches()){x.getResponseHeaders().set("Content-Range","bytes */"+size);x.sendResponseHeaders(416,-1);return;}
            start=Long.parseLong(m.group(1));end=m.group(2).isEmpty()?end:Math.min(end,Long.parseLong(m.group(2)));partial=true;
            if(start> end||start>=size){x.getResponseHeaders().set("Content-Range","bytes */"+size);x.sendResponseHeaders(416,-1);return;}
        }
        x.getResponseHeaders().set("Content-Type","audio/wav");x.getResponseHeaders().set("ETag",etag);x.getResponseHeaders().set("Accept-Ranges","bytes");if(partial)x.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/"+size);
        x.sendResponseHeaders(partial?206:200,end-start+1);
        try(InputStream in=Files.newInputStream(file)){in.skipNBytes(start);byte[] b=new byte[65536];long left=end-start+1;while(left>0){int n=in.read(b,0,(int)Math.min(left,b.length));if(n<0)throw new EOFException();x.getResponseBody().write(b,0,n);left-=n;}}
    }
    private static Map<String,String> query(String q){Map<String,String> out=new HashMap<>();if(q!=null)for(String part:q.split("&")){String[] a=part.split("=",2);out.put(URLDecoder.decode(a[0],StandardCharsets.UTF_8),a.length==2?URLDecoder.decode(a[1],StandardCharsets.UTF_8):"");}return out;}
    public void close(){server.stop(0);executor.shutdownNow();try{store.close();}catch(IOException e){System.err.println("Store close: "+e.getMessage());}}
}
