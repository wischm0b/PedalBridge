#pragma once
#include "history_model.h"
#include <memory>
#include <cmath>
inline bool historySelfTest() {
  using namespace history;
  std::unique_ptr<Tracker> t(new Tracker);
  int a=t->device("02:00:00:00:00:02","Bike A"), b=t->device("00:11:22:33:44:55","Bike B");
  Sample s; s.fresh=true; s.hasPower=s.hasCadence=s.hasSpeed=true;
  s.power=200; s.cadence=90; s.speed=36;
  t->tick(a,s,1000);
  for(uint32_t now=2000;now<=11000;now+=1000) t->tick(a,s,now);
  auto first=t->state.current.stats;
  if(std::abs(first.km-0.1)>0.00001 || first.activeMs!=10000 ||
     first.wattMs/first.powerTime!=200 || first.cadenceMs/60000!=15) return false;
  // Missing samples cannot inflate distance; brief pauses retain the session.
  Sample missing;
  t->tick(a,missing,12000); t->tick(a,s,20000); t->tick(a,s,21000);
  if(std::abs(t->state.current.stats.km-0.11)>0.00001 || t->state.current.id!=1) return false;
  t->setTime(1800000000,21000);
  if(t->state.current.started!=1799999980) return false;
  t->tick(a,missing,321000);
  if(t->state.active || t->state.devices[a].sessions!=1 || t->state.sessions[0].reason!=1) return false;
  // Another bike has separate totals. Restoring an active checkpoint finishes once.
  t->tick(b,s,322000); t->tick(b,s,323000);
  std::unique_ptr<Tracker> restored(new Tracker); restored->state=t->state;
  restored->finish(3); restored->finish(3);
  if(restored->state.devices[b].sessions!=1 || restored->state.devices[a].sessions!=1) return false;
  if(restored->state.sessions[1].reason!=3) return false;
  // Full history retains totals while replacing only old session details.
  uint32_t now=400000;
  Sample stopped=s; stopped.cadence=0;
  for(int i=0;i<140;i++) {
    restored->tick(a,stopped,now); restored->tick(a,stopped,now+2000);
    restored->tick(a,s,now+3000); restored->tick(a,s,now+4000); restored->finish(2); now+=5000;
  }
  if(restored->state.count!=SessionLimit || restored->state.devices[a].sessions!=141) return false;
  // millis() rollover must not corrupt sample duration.
  std::unique_ptr<Tracker> wrap(new Tracker);
  a=wrap->device("02:00:00:00:00:02","Bike");
  wrap->tick(a,s,0xffffff00U); wrap->tick(a,s,744);
  if(wrap->state.current.stats.activeMs!=1000) return false;
  // A bike energy counter reset establishes a new baseline, never a negative delta.
  s.hasEnergy=true; s.energy=100; wrap->tick(a,s,1744);
  s.energy=102; wrap->tick(a,s,2744);
  double before=wrap->state.current.stats.kcal;
  s.energy=0; wrap->tick(a,s,3744);
  s.energy=1; wrap->tick(a,s,4744);
  if(std::abs(wrap->state.current.stats.kcal-before-1)>0.00001) return false;
  // Connection and residual speed/power without cadence cannot start a session.
  std::unique_ptr<Tracker> lifecycle(new Tracker);
  a=lifecycle->device("02:00:00:00:00:02","Bike");
  lifecycle->tick(a,stopped,1000); lifecycle->tick(a,stopped,4000);
  if(lifecycle->state.active || lifecycle->state.nextId!=1) return false;
  lifecycle->tick(a,s,5000); lifecycle->tick(a,s,6000);
  lifecycle->finish(2);
  lifecycle->tick(a,s,7000); lifecycle->tick(a,missing,10000); lifecycle->tick(a,s,11000);
  if(lifecycle->state.active || !lifecycle->waitingForStop() || lifecycle->state.count!=1) return false;
  lifecycle->tick(a,stopped,12000); lifecycle->tick(a,missing,13000);
  lifecycle->tick(a,stopped,14000); lifecycle->tick(a,s,15000);
  if(lifecycle->state.active) return false;
  lifecycle->tick(a,stopped,16000); lifecycle->tick(a,stopped,18000);
  if(lifecycle->waitingForStop() || lifecycle->state.active) return false;
  lifecycle->tick(a,s,19000); lifecycle->tick(a,s,20000);
  return lifecycle->state.active && lifecycle->state.current.id==2 &&
      lifecycle->state.current.stats.activeMs==1000;
}
