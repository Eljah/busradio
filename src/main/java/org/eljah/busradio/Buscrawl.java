package org.eljah.busradio;

import java.time.Instant;
import java.util.*;
import org.eljah.busradio.Model.*;

/** Adapter for Eljah/buscrawl, reviewed at 0411483d040356a9a53a7ff254d4bd4b34f021b0. */
public final class Buscrawl {
    private Buscrawl(){}
    public static List<Route> routes(String json){
        var root=Json.object(Json.parse(json));var stops=Json.object(root.get("nbusstop"));var buses=Json.object(root.get("bus"));var sequences=Json.object(root.get("route"));
        record Stop(int order,Point point){}
        Map<String,List<Stop>> groups=new TreeMap<>();Map<String,String> routeIds=new HashMap<>();Map<String,Integer> dirs=new HashMap<>();
        for(Object value:sequences.values()){
            var row=Json.array(value);String routeId=row.get(0).toString(),stopId=row.get(1).toString();int dir=((Number)row.get(2)).intValue(),order=((Number)row.get(3)).intValue();
            if(!buses.containsKey(routeId)||!stops.containsKey(stopId))continue;
            var stop=Json.array(stops.get(stopId));Point p=new Point(((Number)stop.get(2)).doubleValue(),((Number)stop.get(1)).doubleValue());
            String key=routeId+"~"+dir;groups.computeIfAbsent(key,k->new ArrayList<>()).add(new Stop(order,p));routeIds.put(key,routeId);dirs.put(key,dir);
        }
        List<Route> out=new ArrayList<>();
        groups.forEach((key,seq)->{
            seq.sort(Comparator.comparingInt(Stop::order));
            for(int i=1;i<seq.size();i++)if(seq.get(i).order()!=seq.get(i-1).order()+1)throw new IllegalArgumentException("Non-consecutive stop order: "+key);
            if(seq.size()<2)return;String id=routeIds.get(key);var meta=Json.array(buses.get(id));String number=meta.get(1).toString();int type=((Number)meta.get(5)).intValue();
            if((type==1||type==2)&&!number.startsWith("Т"))number="Т"+number;
            out.add(new Route(id,number,dirs.get(key),"stop-chain",seq.stream().map(Stop::point).toList()));
        });return List.copyOf(out);
    }
    public static Fix position(String json,String expectedPlate){
        var m=Json.object(Json.parse(json));if(!expectedPlate.equals(Json.string(m,"plate")))throw new IllegalArgumentException("Different vehicle");
        // sourceTimestamp is measurement time, timestamp is only ingestion time.
        String timeKey=m.containsKey("sourceTimestamp")?"sourceTimestamp":"timestamp";
        return new Fix(Json.number(m,"latitude"),Json.number(m,"longitude"),Json.number(m,"speed")/3.6,Json.number(m,"course"),50,Instant.ofEpochSecond((long)Json.number(m,timeKey)));
    }
}
