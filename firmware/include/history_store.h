#pragma once
#include <Arduino.h>
#include <LittleFS.h>
#include <Preferences.h>
#include <memory>
#include "history_model.h"

namespace history {
class Store {
 public:
  Tracker tracker;
  String error;
  bool ready = false;
  uint32_t savedAt = 0;
  String rootPrefix;
  String path(const char* name) const { return rootPrefix+name; }
  void begin(Preferences& preferences, bool recoverActive=true) {
    // This firmware is the first user of the existing filesystem partition.
    // Once initialized, never auto-format on mount failure.
    ready = LittleFS.begin(false);
    if (!ready && !preferences.getBool("historyFs",false)) ready = LittleFS.begin(true);
    if (!ready) { error = "Historien-Speicher nicht verfügbar"; return; }
    preferences.putBool("historyFs",true);
    if(!rootPrefix.isEmpty()) LittleFS.mkdir(rootPrefix);
    std::unique_ptr<State> candidate(new State);
    bool found = false, existed = false;
    for (int i=0;i<2;++i) {
      String filePath = path(i ? "/history1.bin" : "/history0.bin");
      existed |= LittleFS.exists(filePath);
      if (read(filePath.c_str(),*candidate) && (!found || candidate->generation > tracker.state.generation)) {
        tracker.state = *candidate; found = true;
      }
    }
    if (existed && !found) {
      ready = false; error = "Historie beschädigt; Dateien bleiben erhalten"; return;
    }
    // A saved in-progress ride becomes an interrupted session exactly once.
    if (recoverActive && tracker.state.active) { tracker.finish(3); save(true); }
    if (!found) { tracker.dirty=true; save(true); }
  }
  void save(bool force=false) {
    if (!ready || !tracker.dirty || (!force && millis()-savedAt<30000)) return;
    auto& s=tracker.state; ++s.generation;
    File f=LittleFS.open(path(s.generation%2 ? "/history1.bin" : "/history0.bin"),"w");
    const uint32_t sum=checksum(s);
    bool ok = f && f.write(reinterpret_cast<const uint8_t*>(&s),sizeof(s))==sizeof(s);
    if (ok) ok=f.write(reinterpret_cast<const uint8_t*>(&sum),sizeof(sum))==sizeof(sum);
    f.flush(); f.close();
    savedAt=millis();
    if (ok) { tracker.dirty=false; error=""; }
    else error="Historie konnte nicht gespeichert werden";
  }
  static String quote(const char* value) {
    String out="\"";
    for (const unsigned char* p=reinterpret_cast<const unsigned char*>(value);*p;++p) {
      if (*p=='"' || *p=='\\') { out+='\\'; out+=char(*p); }
      else if (*p>=32) out+=char(*p);
    }
    return out+'"';
  }
  static String stats(const Stats& s) {
    String j="{\"seconds\":"+String(s.elapsedMs/1000,1);
    j+=",\"active\":"+String(s.activeMs/1000,1);
    j+=",\"km\":"+String(s.km,4)+",\"kcal\":"+String(s.kcal,2);
    j+=",\"power\":"+(s.powerTime>0 ? String(s.wattMs/s.powerTime,1):String("null"));
    j+=",\"cadence\":"+(s.cadenceTime>0 ? String(s.cadenceMs/s.cadenceTime,1):String("null"));
    j+=",\"speed\":"+(s.speedTime>0 ? String(s.speedMs/s.speedTime,2):String("null"));
    j+=",\"maxPower\":"+String(s.maxPower,1)+",\"maxCadence\":"+String(s.maxCadence,1);
    j+=",\"maxSpeed\":"+String(s.maxSpeed,2)+",\"workKj\":"+String(s.wattMs/1000000,2);
    j+=",\"revolutions\":"+String(s.cadenceMs/60000,1);
    j+=",\"estimated\":"+String(s.estimatedCalories ? "true":"false")+"}";
    return j;
  }
  static String session(const Session& s) {
    return "{\"id\":"+String(s.id)+",\"device\":"+String(s.device)+
      ",\"started\":"+String(s.started)+",\"reason\":"+String(s.reason)+",\"stats\":"+stats(s.stats)+"}";
  }
  String json() const {
    const auto& s=tracker.state;
    String j; j.reserve(60000);
    j="{\"version\":1,\"error\":"+quote(error.c_str())+",\"devices\":[";
    for (unsigned i=0;i<s.deviceCount;++i) {
      if(i) j+=',';
      auto totals=s.devices[i].totals;
      bool active=s.active && s.current.device==i;
      if(active) totals.add(s.current.stats);
      j+="{\"address\":"+quote(s.devices[i].address)+",\"name\":"+quote(s.devices[i].name);
      j+=",\"sessions\":"+String(s.devices[i].sessions)+",\"active\":"+String(active?"true":"false");
      j+=",\"stats\":"+stats(totals)+"}";
    }
    j+="],\"current\":"+(s.active ? session(s.current):String("null"))+",\"sessions\":[";
    for(unsigned i=0;i<s.count;++i) {
      if(i) j+=',';
      j+=session(s.sessions[(s.next+SessionLimit-1-i)%SessionLimit]);
    }
    j+="],\"limit\":128,\"deviceLimit\":16}";
    return j;
  }
 private:
  static uint32_t checksum(const State& s) {
    uint32_t h=2166136261U;
    for(size_t i=0;i<sizeof(s);++i) h=(h^reinterpret_cast<const uint8_t*>(&s)[i])*16777619U;
    return h;
  }
  static bool read(const char* path, State& s) {
    File f=LittleFS.open(path,"r"); uint32_t sum=0;
    if(!f || f.size()!=sizeof(s)+sizeof(sum)) return false;
    if(f.read(reinterpret_cast<uint8_t*>(&s),sizeof(s))!=sizeof(s)) return false;
    if(f.read(reinterpret_cast<uint8_t*>(&sum),sizeof(sum))!=sizeof(sum)) return false;
    if(s.magic!=0x48495331 || s.version!=1 || checksum(s)!=sum ||
       s.deviceCount>DeviceLimit || s.count>SessionLimit || s.next>=SessionLimit ||
       (s.active && s.current.device>=s.deviceCount)) return false;
    for(unsigned i=0;i<s.deviceCount;++i)
      if(s.devices[i].address[17] || s.devices[i].name[47]) return false;
    for(unsigned i=0;i<s.count;++i) if(s.sessions[i].device>=s.deviceCount) return false;
    return true;
  }
};
}
