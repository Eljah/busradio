package io.github.eljah.busradio.core;
import java.time.*;
import java.util.*;
import static io.github.eljah.busradio.core.Model.*;
import static io.github.eljah.busradio.core.Json.*;
/** Independent adapter for Eljah/buscrawl 0411483d; no Spark or copied crawler runtime. */
public final class BuscrawlAdapter {
 private BuscrawlAdapter(){}
 public record ImportResult(List<Route> routes,List<String> warnings){}
 public static ImportResult routes(Object value){
  var root=obj(value);var stops=obj(root.get("nbusstop"));var buses=obj(root.get("bus"));var order=obj(root.get("route"));Map<String,TreeMap<Integer,Point>> groups=new TreeMap<>();Map<String,String> names=new HashMap<>();List<String>warnings=new ArrayList<>();
  for(var entry:order.entrySet()){
   var row=arr(entry.getValue());if(row.size()<4)throw new IllegalArgumentException("route row length < 4");String bus=integer(row.get(0)),stop=integer(row.get(1)),direction=integer(row.get(2));int index=Integer.parseInt(integer(row.get(3)));
   if(!buses.containsKey(bus)||!stops.containsKey(stop)){warnings.add("Skipped missing bus/stop in row "+entry.getKey());continue;}
   var b=arr(buses.get(bus));var s=arr(stops.get(stop));String id=bus+":"+direction;
   // buscrawl stop tuple is [name, longitude, latitude], NOT GeoJSON [lat,lon].
   Point p=new Point(number(s.get(2)),number(s.get(1)));var sorted=groups.computeIfAbsent(id,k->new TreeMap<>());
   if(sorted.putIfAbsent(index,p)!=null)throw new IllegalArgumentException("Duplicate stop order "+id+":"+index);
   names.put(id,b.get(1).toString()+" / direction "+direction);
  }
  List<Route> result=new ArrayList<>();for(var e:groups.entrySet()){if(e.getValue().size()<2){warnings.add("Skipped one-stop route "+e.getKey());continue;}Integer prev=null;boolean gap=false;for(int n:e.getValue().keySet()){if(prev!=null&&n!=prev+1)gap=true;prev=n;}if(gap){warnings.add("Skipped route with missing stop order "+e.getKey());continue;}result.add(new Route(e.getKey(),names.get(e.getKey()),e.getKey().substring(e.getKey().indexOf(':')+1),"STOP_CHORDS",List.copyOf(e.getValue().values())));}
  warnings.add("STOP_CHORDS are stop-to-stop chords, not road shapes; review/replace with SHAPE before deployment.");return new ImportResult(List.copyOf(result),List.copyOf(warnings));
 }
 private static String integer(Object x){double n=number(x);if(n!=Math.rint(n))throw new IllegalArgumentException("Integer tuple field required");return Long.toString((long)n);}
 private static double number(Object x){double d=x instanceof Number n?n.doubleValue():Double.parseDouble(x.toString());if(!Double.isFinite(d))throw new IllegalArgumentException("Non-finite coordinate");return d;}
 public static Fix movement(Object value,String vehicle){
  var m=obj(value);if(!vehicle.equals(str(m,"plate",""))&&!vehicle.equals(str(m,"naviUnitId","")))throw new IllegalArgumentException("Different vehicle");
  // Navi ingestion time is NOT the GNSS sample time. Refuse Navi rows lacking sourceTimestamp.
  String source=str(m,"source", "legacy-buscrawl");String timeKey=m.containsKey("sourceTimestamp")?"sourceTimestamp":"timestamp";if(source.equals("navi")&&!m.containsKey("sourceTimestamp"))throw new IllegalArgumentException("Navi sourceTimestamp required");
  double course=num(m,"course",0);course=((course%360)+360)%360;
  return new Fix(new Point(num(m,"latitude"),num(m,"longitude")),num(m,"speed",0)/3.6,course,num(m,"accuracyM",50),Instant.ofEpochSecond(lng(m,timeKey)),"buscrawl:"+source);
 }
}
