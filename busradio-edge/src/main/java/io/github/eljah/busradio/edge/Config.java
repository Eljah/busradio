package io.github.eljah.busradio.edge;
import io.github.eljah.busradio.core.FilesEx;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
public final class Config {
 private final Properties p;
 public Config(Properties p){this.p=p;FilesEx.id(get("bus.id",""));URI u=server();if(u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||u.getHost()==null||(!u.getPath().isEmpty()&&!u.getPath().equals("/")))throw new IllegalArgumentException("Server must be an origin without credentials/path");if(!u.getScheme().equals("https")&&!(u.getScheme().equals("http")&&isLoopback()))throw new IllegalArgumentException("HTTPS required except for loopback demo");if(get("bus.token","").length()<24||get("server.publicKey","").isBlank())throw new IllegalArgumentException("Device enrollment token and pinned public key required");ZoneId.of(get("sync.zone","Europe/Moscow"));LocalTime.parse(get("sync.start","01:00"));LocalTime.parse(get("sync.end","05:00"));if(bool("sync.demoLoopback",false)&&!isLoopback())throw new IllegalArgumentException("Demo network bypass is restricted to loopback");}
 public static Config load(Path file)throws IOException{Properties p=new Properties();try(Reader r=Files.newBufferedReader(file)){p.load(r);}for(var e:System.getenv().entrySet())if(e.getKey().startsWith("BR_EDGE_"))p.setProperty(e.getKey().substring(8).toLowerCase(Locale.ROOT).replace('_','.'),e.getValue());return new Config(p);}
 public String get(String key,String d){return p.getProperty(key,p.getProperty(key.toLowerCase(Locale.ROOT),d));}
 public boolean bool(String key,boolean d){String v=get(key,Boolean.toString(d));if(!v.equals("true")&&!v.equals("false"))throw new IllegalArgumentException("Invalid boolean "+key);return Boolean.parseBoolean(v);}
 public int integer(String key,int d){return Integer.parseInt(get(key,Integer.toString(d)));}
 public URI server(){return URI.create(get("server","http://127.0.0.1:8080").replaceAll("/$",""));}
 public String bus(){return get("bus.id","");}public Path cache(){return Path.of(get("cache.dir","var/edge"));}
 public boolean isLoopback(){String h=server().getHost();return "127.0.0.1".equals(h)||"localhost".equalsIgnoreCase(h)||"[::1]".equals(h)||"::1".equals(h);}
}
