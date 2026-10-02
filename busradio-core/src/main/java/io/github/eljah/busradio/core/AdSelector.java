package io.github.eljah.busradio.core;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import static io.github.eljah.busradio.core.Model.*;
/** Deterministic, offline, route-direction-aware eligibility. Never substitutes stale GPS. */
public final class AdSelector {
 public record Candidate(Campaign campaign,double aheadM,double distanceM,double etaSeconds){}
 public Optional<Candidate> choose(Manifest m,Fix fix,Instant now,long remainingFrames,Predicate<Campaign> frequencyAllowed){
  if(fix==null||!fix.fresh(now))return Optional.empty();var projected=Geo.match(m.route(),fix);if(projected.isEmpty())return Optional.empty();var bus=projected.get();var assets=m.assetMap();List<Candidate> eligible=new ArrayList<>();
  for(Campaign c:m.campaigns()){
   Asset asset=assets.get(c.assetId());
   if(!c.enabled()||!c.routeIds().contains(m.route().id())||now.isBefore(c.startsAt())||!now.isBefore(c.endsAt())||asset==null||asset.frames()>remainingFrames||!frequencyAllowed.test(c))continue;
   if(!Model.inWindow(now.atZone(ZoneId.of(c.zone())).toLocalTime(),LocalTime.parse(c.dailyStart()),LocalTime.parse(c.dailyEnd())))continue;
   double direct=Geo.distance(fix.point(),c.point());if(direct>c.radiusM())continue;
   var poi=Geo.projections(m.route(),c.point()).stream().min(Comparator.comparingDouble(Geo.Projection::crossM));if(poi.isEmpty()||poi.get().crossM()>c.radiusM())continue;
   double ahead=poi.get().alongM()-bus.alongM();if(ahead < -25||ahead>c.maxAheadM())continue;
   double eta=fix.speedMps()>=1?Math.max(0,ahead)/fix.speedMps():0;
   // Do not start a long commercial that finishes after a fast-moving bus has passed its target.
   if(fix.speedMps()>=1&&(eta>c.horizonSeconds()||(ahead>25&&eta<asset.frames()/(double)SAMPLE_RATE)))continue;
   eligible.add(new Candidate(c,ahead,direct,eta));
  }
  return eligible.stream().min(Comparator.<Candidate>comparingInt(x->-x.campaign().priority()).thenComparingDouble(x->Math.max(0,x.aheadM())).thenComparing(x->x.campaign().id()));
 }
}
