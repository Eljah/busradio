package io.github.eljah.busradio.core;
import java.time.Instant;
import java.util.*;
import static io.github.eljah.busradio.core.Model.*;
/** Resolve outbound/return shapes locally; retain a direction at stops only after a moving fix. */
public final class DirectionResolver {
 private String lastRoute;
 private record Match(Route route,Geo.Projection projection,double score){}
 public Optional<Route> resolve(Manifest manifest,Fix fix,Instant now){
  if(fix==null||!fix.fresh(now))return Optional.empty();
  if(fix.speedMps()<2){return manifest.allRoutes().stream().filter(r->r.id().equals(lastRoute)&&Geo.match(r,fix).isPresent()).findFirst();}
  List<Match> matches=new ArrayList<>();
  for(Route r:manifest.allRoutes())Geo.match(r,fix).ifPresent(p->matches.add(new Match(r,p,p.crossM()+Geo.angle(p.bearing(),fix.course())*2)));
  matches.sort(Comparator.comparingDouble(Match::score));if(matches.isEmpty())return Optional.empty();
  if(matches.size()>1&&matches.get(1).score()-matches.get(0).score()<15&&!matches.get(0).route().id().equals(lastRoute))return Optional.empty();
  lastRoute=matches.getFirst().route().id();return Optional.of(matches.getFirst().route());
 }
}
