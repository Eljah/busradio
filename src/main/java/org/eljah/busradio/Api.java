package org.eljah.busradio;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class Api implements AutoCloseable {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"http-deadlines");t.setDaemon(true);return t;});
    private final String base,token,busId;
    public Api(String base,String token,String busId,boolean allowHttp){
        URI u=URI.create(base);String host=u.getHost();boolean loopback=Set.of("localhost","127.0.0.1","[::1]","::1").contains(Objects.toString(host,""));
        if(!"https".equals(u.getScheme())&&!("http".equals(u.getScheme())&&(loopback||allowHttp)))throw new IllegalArgumentException("HTTPS is required outside loopback");
        if(host==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)throw new IllegalArgumentException("Invalid server URL");
        this.base=base.replaceAll("/+$","");this.token=token;this.busId=busId;
    }
    public final class Response implements AutoCloseable {
        public final HttpResponse<InputStream> raw;private final ScheduledFuture<?> deadline;
        Response(HttpResponse<InputStream> r){raw=r;deadline=deadlines.schedule(()->{try{r.body().close();}catch(IOException ignored){}},120,TimeUnit.SECONDS);}
        public String text(int max)throws IOException{return new String(Support.limited(raw.body(),max),StandardCharsets.UTF_8);}
        public void close()throws IOException{deadline.cancel(false);raw.body().close();}
    }
    public Response request(String method,String path,byte[] body,Map<String,String> headers)throws IOException,InterruptedException{
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(120)).header("Authorization","Bearer "+token);
        if(!busId.isEmpty())b.header("X-Bus-Id",busId);headers.forEach(b::header);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));return new Response(client.send(b.build(),HttpResponse.BodyHandlers.ofInputStream()));
    }
    public Object json(String method,String path,Object payload)throws IOException,InterruptedException{
        try(Response r=request(method,path,payload==null?null:Json.write(payload).getBytes(StandardCharsets.UTF_8),Map.of("Content-Type","application/json"))){
            String text=r.text(16777216);if(r.raw.statusCode()/100!=2)throw new IOException("HTTP "+r.raw.statusCode()+" on "+path+": "+text.substring(0,Math.min(200,text.length())));return Json.parse(text);
        }
    }
    public void close(){client.shutdownNow();deadlines.shutdownNow();}
}
