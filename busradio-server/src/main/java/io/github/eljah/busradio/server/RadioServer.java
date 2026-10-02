package io.github.eljah.busradio.server;
import com.sun.net.httpserver.*;
import io.github.eljah.busradio.core.*;
import static io.github.eljah.busradio.core.Json.*;
import static io.github.eljah.busradio.core.Model.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Authenticated administration and per-device content API; no runtime web framework required. */
public final class RadioServer implements AutoCloseable {
 private final Store store;private final HttpServer http;private final ExecutorService executor;
 private final String password,origin,ffmpeg;private final boolean secure;
 private record Session(String csrf,Instant expires){}
 private final ConcurrentHashMap<String,Session> sessions=new ConcurrentHashMap<>();
 private final ConcurrentHashMap<String,Instant> nextLogin=new ConcurrentHashMap<>();
 private final Semaphore uploads=new Semaphore(2);
 public RadioServer(Store store,String bind,int port,String password,String publicOrigin,String ffmpeg)throws IOException{
  if(password==null||password.length()<12)throw new IllegalArgumentException("BR_ADMIN_PASSWORD must contain at least 12 characters");
  this.store=store;this.password=password;this.ffmpeg=ffmpeg;http=HttpServer.create(new InetSocketAddress(bind,port),64);
  this.origin=publicOrigin==null||publicOrigin.isBlank()?"http://127.0.0.1:"+http.getAddress().getPort():publicOrigin;secure=origin.startsWith("https://");
  executor=Executors.newFixedThreadPool(16);http.setExecutor(executor);http.createContext("/",this::handle);
 }
 public void start(){http.start();}public int port(){return http.getAddress().getPort();}
 private void handle(HttpExchange x){try{
  var h=x.getResponseHeaders();h.set("X-Content-Type-Options","nosniff");h.set("Cache-Control","no-store");h.set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'");h.set("Referrer-Policy","no-referrer");
  String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
  if(path.equals("/health")&&method.equals("GET")){json(x,200,Map.of("status","ok"));return;}
  if(path.equals("/api/login")&&method.equals("POST")){origin(x);login(x);return;}
  if(path.startsWith("/api/bus/")){device(x,path,method);return;}
  if(path.startsWith("/api/")){
   Session session=session(x);if(session==null){json(x,401,Map.of("error","Authentication required"));return;}
   if(!method.equals("GET")){origin(x);if(!FilesEx.same(session.csrf(),x.getRequestHeaders().getFirst("X-CSRF-Token")))throw new SecurityException("CSRF check failed");}
   if(path.equals("/api/me")&&method.equals("GET")){json(x,200,Map.of("user","admin","csrf",session.csrf()));return;}
   if(path.equals("/api/logout")&&method.equals("POST")){sessions.remove(cookie(x));x.getResponseHeaders().add("Set-Cookie","BRSESSION=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0"+(secure?"; Secure":""));json(x,200,Map.of("ok",true));return;}
   admin(x,path,method);return;
  }
  if(!method.equals("GET")){json(x,405,Map.of("error","Method not allowed"));return;}
  staticFile(x,path);
 }catch(SecurityException e){error(x,403,e.getMessage());}catch(IllegalArgumentException|NoSuchElementException e){error(x,400,e.getMessage());}catch(Exception e){System.err.println("HTTP failure: "+e.getClass().getSimpleName()+": "+e.getMessage());error(x,500,"Server operation failed; inspect server log");}finally{x.close();}}
 private void origin(HttpExchange x){String supplied=x.getRequestHeaders().getFirst("Origin");if(supplied!=null&&!supplied.equals(origin))throw new SecurityException("Origin mismatch; configure BR_PUBLIC_ORIGIN");}
 private void login(HttpExchange x)throws IOException{
  String ip=x.getRemoteAddress().getAddress().getHostAddress();Instant now=Instant.now();Instant next=nextLogin.put(ip,now.plusSeconds(1));if(nextLogin.size()>2000)nextLogin.entrySet().removeIf(e->e.getValue().isBefore(now));
  if(next!=null&&now.isBefore(next)){json(x,429,Map.of("error","Retry login later"));return;}
  var request=body(x,8192);if(!FilesEx.same(str(request,"password"),password)||!str(request,"username","admin").equals("admin")){json(x,401,Map.of("error","Invalid credentials"));return;}
  sessions.entrySet().removeIf(e->e.getValue().expires().isBefore(now));if(sessions.size()>=64){json(x,429,Map.of("error","Session limit"));return;}String token=FilesEx.token(),csrf=FilesEx.token();sessions.put(token,new Session(csrf,now.plus(Duration.ofHours(8))));
  x.getResponseHeaders().add("Set-Cookie","BRSESSION="+token+"; Path=/; HttpOnly; SameSite=Strict; Max-Age=28800"+(secure?"; Secure":""));json(x,200,Map.of("csrf",csrf));
 }
 private static String cookie(HttpExchange x){String cookie=x.getRequestHeaders().getFirst("Cookie");if(cookie==null)return "";for(String part:cookie.split(";")){String[] p=part.trim().split("=",2);if(p.length==2&&p[0].equals("BRSESSION"))return p[1];}return "";}
 private Session session(HttpExchange x){var s=sessions.get(cookie(x));return s!=null&&s.expires().isAfter(Instant.now())?s:null;}
 private void admin(HttpExchange x,String path,String method)throws Exception{
  if(path.equals("/api/admin/state")&&method.equals("GET")){json(x,200,store.adminState());return;}
  if(path.equals("/api/admin/media")&&method.equals("POST")){
   if(!uploads.tryAcquire()){json(x,429,Map.of("error","Decoder busy"));return;}try{var q=query(x);json(x,201,AudioImporter.upload(store,x.getRequestBody(),q.get("name"),q.get("kind"),q.get("rights"),ffmpeg));}finally{uploads.release();}return;
  }
  if(path.equals("/api/admin/playlists")&&method.equals("POST")){var p=Playlist.from(body(x,1_000_000));var assets=obj(store.snapshot().get("assets"));for(var s:p.slots()){if(s.type().equals("MUSIC")&&!assets.containsKey(s.assetId()))throw new IllegalArgumentException("Unknown music asset");if(!s.fallbackAssetId().isBlank()&&!assets.containsKey(s.fallbackAssetId()))throw new IllegalArgumentException("Unknown fallback asset");}store.put("playlists",p.id(),p);json(x,200,p);return;}
  if(path.equals("/api/admin/campaigns")&&method.equals("POST")){var c=Campaign.from(body(x,65536));var snapshot=store.snapshot();Asset asset=Asset.from(obj(snapshot.get("assets")).get(c.assetId()));if(!asset.kind().equals("AD"))throw new IllegalArgumentException("Select an advertisement asset");for(String id:c.routeIds())if(!obj(snapshot.get("routes")).containsKey(id))throw new IllegalArgumentException("Unknown route direction "+id);store.put("campaigns",c.id(),c);json(x,200,c);return;}
  if(path.equals("/api/admin/routes/import")&&method.equals("POST")){var report=BuscrawlAdapter.routes(body(x,4_000_000));store.edit(s->{for(Route r:report.routes())obj(s.get("routes")).put(r.id(),r);return null;});json(x,200,report);return;}
  if(path.equals("/api/admin/routes")&&method.equals("POST")){var r=Route.from(body(x,2_000_000));store.put("routes",r.id(),r);json(x,200,r);return;}
  if(path.equals("/api/admin/movement/preview")&&method.equals("POST")){var b=body(x,16384);var f=BuscrawlAdapter.movement(b.get("row"),str(b,"vehicle"));json(x,200,Map.of("fix",f,"fresh",f.fresh(Instant.now())));return;}
  if(path.equals("/api/admin/buses")&&method.equals("POST")){
   var b=body(x,16384);String id=FilesEx.id(str(b,"id")),playlist=FilesEx.id(str(b,"playlistId")),route=FilesEx.id(str(b,"routeId"));String token=FilesEx.token();
   boolean created=store.edit(s->{if(!obj(s.get("playlists")).containsKey(playlist)||!obj(s.get("routes")).containsKey(route))throw new IllegalArgumentException("Unknown playlist or route");var buses=obj(s.get("buses"));boolean isNew=!buses.containsKey(id);var v=isNew?new LinkedHashMap<String,Object>():new LinkedHashMap<>(obj(buses.get(id)));v.put("id",id);v.put("playlistId",playlist);v.put("routeId",route);if(isNew)v.put("tokenHash",FilesEx.sha(token.getBytes(StandardCharsets.UTF_8)));buses.put(id,v);return isNew;});
   json(x,200,created?Map.of("id",id,"token",token,"publicKey",store.publicKey):Map.of("id",id,"updated",true));return;
  }
  if(path.equals("/api/admin/buses/rotate-token")&&method.equals("POST")){String id=FilesEx.id(str(body(x,8192),"id")),token=FilesEx.token();store.edit(s->{obj(obj(s.get("buses")).get(id)).put("tokenHash",FilesEx.sha(token.getBytes(StandardCharsets.UTF_8)));return null;});json(x,200,Map.of("id",id,"token",token));return;}
  if(path.equals("/api/admin/publish")&&method.equals("POST")){json(x,200,store.publish(str(body(x,8192),"busId")));return;}
  json(x,404,Map.of("error","Unknown admin endpoint"));
 }
 private void device(HttpExchange x,String path,String method)throws Exception{
  String[] p=path.split("/");if(p.length<5){json(x,404,Map.of("error","Unknown device endpoint"));return;}String bus=FilesEx.id(p[3]);String auth=x.getRequestHeaders().getFirst("Authorization"),token=auth!=null&&auth.startsWith("Bearer ")?auth.substring(7):null;
  if(!store.authorized(bus,token)){json(x,401,Map.of("error","Invalid device credentials"));return;}
  if(p.length==5&&p[4].equals("manifest")&&method.equals("GET")){json(x,200,store.envelope(bus));return;}
  if(p.length==6&&p[4].equals("media")&&method.equals("GET")){media(x,store.authorizedMedia(bus,p[5]),p[5]);return;}
  if(p.length==5&&p[4].equals("events")&&method.equals("POST")){json(x,200,Map.of("accepted",store.acceptEvents(bus,arr(body(x,1_000_000).get("events")))));return;}
  if(p.length==5&&p[4].equals("heartbeat")&&method.equals("POST")){var b=body(x,16384);String revision=str(b,"revision","");store.edit(s->{var v=obj(obj(s.get("buses")).get(bus));v.put("lastSeen",Instant.now().toString());v.put("activeRevision",revision);v.put("queuedEvents",num(b,"queuedEvents",0));v.put("mode",str(b,"mode","unknown"));return null;});json(x,200,Map.of("ok",true));return;}
  json(x,404,Map.of("error","Unknown device endpoint"));
 }
 private void media(HttpExchange x,Path file,String hash)throws IOException{
  long length=Files.size(file),start=0,end=length-1;int status=200;String range=x.getRequestHeaders().getFirst("Range");
  if(range!=null){if(!range.matches("bytes=[0-9]+-[0-9]*")){x.getResponseHeaders().set("Content-Range","bytes */"+length);json(x,416,Map.of("error","Unsupported range"));return;}String[] parts=range.substring(6).split("-",-1);start=Long.parseLong(parts[0]);if(!parts[1].isEmpty())end=Math.min(end,Long.parseLong(parts[1]));if(start>end||start>=length){x.getResponseHeaders().set("Content-Range","bytes */"+length);json(x,416,Map.of("error","Unsatisfiable range"));return;}status=206;x.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/"+length);}
  x.getResponseHeaders().set("ETag","\""+hash+"\"");x.getResponseHeaders().set("Accept-Ranges","bytes");x.getResponseHeaders().set("Content-Type","application/octet-stream");x.sendResponseHeaders(status,end-start+1);
  try(InputStream in=Files.newInputStream(file);OutputStream out=x.getResponseBody()){in.skipNBytes(start);long remaining=end-start+1;byte[] buffer=new byte[65536];while(remaining>0){int n=in.read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new EOFException();out.write(buffer,0,n);remaining-=n;}}
 }
 private void staticFile(HttpExchange x,String path)throws IOException{if(path.equals("/"))path="/index.html";if(!Set.of("/index.html","/app.js","/app.css").contains(path)){json(x,404,Map.of("error","Not found"));return;}try(InputStream in=getClass().getResourceAsStream("/web"+path)){if(in==null)throw new IOException("Missing UI resources");byte[] bytes=in.readAllBytes();x.getResponseHeaders().set("Content-Type",path.endsWith(".js")?"text/javascript; charset=utf-8":path.endsWith(".css")?"text/css; charset=utf-8":"text/html; charset=utf-8");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);}}
 private static Map<String,String> query(HttpExchange x){Map<String,String> m=new HashMap<>();String q=x.getRequestURI().getRawQuery();if(q!=null)for(String part:q.split("&")){String[] p=part.split("=",2);m.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length==2?URLDecoder.decode(p[1],StandardCharsets.UTF_8):"");}return m;}
 private static Map<String,Object> body(HttpExchange x,int max)throws IOException{return obj(Json.parse(new String(FilesEx.limited(x.getRequestBody(),max),StandardCharsets.UTF_8)));}
 private static void json(HttpExchange x,int status,Object o)throws IOException{byte[] b=Json.write(o).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,b.length);x.getResponseBody().write(b);}
 private static void error(HttpExchange x,int status,String message){try{json(x,status,Map.of("error",message==null?"Invalid request":message));}catch(IOException ignored){}}
 @Override public void close(){http.stop(0);executor.shutdownNow();}
}
