#pragma once
#include "history_store.h"
#include <vector>

namespace history {
constexpr unsigned ArchiveLimit=1000;
constexpr size_t FreeReserve=256*1024;
struct SummaryRecord { Session session; uint32_t checksum; };
struct __attribute__((packed)) RawHeader {
  uint32_t magic=0x31574152, id=0, started=0;
  uint16_t recordBytes=24, flags=0; // flags: 1 recording gaps/capacity, 2 interrupted
};
struct __attribute__((packed)) RawRecord {
  uint32_t elapsedMs;
  int16_t power;
  uint16_t cadence10, speed100, bikeEnergy;
  uint32_t distanceMeters, calories1000;
  uint16_t flags, reserved;
};
static_assert(sizeof(RawRecord)==24,"raw format");
inline uint32_t hashBytes(const void* bytes,size_t size,uint32_t h=2166136261U) {
  auto p=static_cast<const uint8_t*>(bytes);
  while(size--) h=(h^*p++)*16777619U;
  return h;
}
struct RawFile { uint32_t id,bytes; uint16_t flags; };
class Archive : public Store {
 public:
  String archiveId;
  uint32_t importChecksum=0;
  uint8_t ledBrightness=20,ledPattern=0,idleMinutes=5;
  bool recordSeconds=true;
  uint32_t summaryCount=0,lastSummary=0,pressureDeletions=0,lastAck=0;
  bool lastAckOk=false,rawPaused=false;
  size_t rawReserve=FreeReserve;
  String managementError;
  void begin(Preferences& prefs) {
    preferences=&prefs;
    ledBrightness=std::min<uint8_t>(100,prefs.getUChar("ledBrightness",20));
    ledPattern=std::min<uint8_t>(3,prefs.getUChar("ledPattern",0));
    idleMinutes=std::max<uint8_t>(1,std::min<uint8_t>(30,prefs.getUChar("idleMinutes",5)));
    recordSeconds=prefs.getBool("recordSeconds",true);
    tracker.idleTimeoutMs=idleMinutes*60000UL;
    pressureDeletions=prefs.getUInt("rawEvicted",0);
    archiveId=prefs.getString("archiveId","");
    if(archiveId.isEmpty()) {
      char id[40]; snprintf(id,sizeof(id),"%012llx-%08lx",ESP.getEfuseMac(),(unsigned long)esp_random());
      archiveId=id; prefs.putString("archiveId",archiveId);
    }
    Store::begin(prefs,false);
    if(!ready) return;
    if(!loadJournal()) { ready=false; return; }
    // Replay journal entries newer than the atomic legacy checkpoint.
    const auto& s=tracker.state;
    uint32_t checkpoint=s.count ? s.sessions[(s.next+SessionLimit-1)%SessionLimit].id:0;
    File journal=LittleFS.open(path("/summaries.bin"),"r"); SummaryRecord rec;
    while(readRecord(journal,rec)) {
      if(rec.session.id>checkpoint) {
        if(rec.session.device>=tracker.state.deviceCount) {
          error="Archiv-Gerätezuordnung beschädigt"; ready=false; journal.close(); return;
        }
        tracker.restoreFinished(rec.session);
      }
    }
    journal.close();
    if(tracker.state.active) {
      markRaw(tracker.state.current.id,2); tracker.finish(3);
    }
    save(true); // Migrates legacy sessions, retaining IDs and lifetime totals.
  }
  bool settings(uint8_t brightness,uint8_t pattern,uint8_t minutes,bool seconds) {
    if(brightness>100 || pattern>3 || minutes<1 || minutes>30) return false;
    ledBrightness=brightness; ledPattern=pattern; idleMinutes=minutes; recordSeconds=seconds;
    tracker.idleTimeoutMs=minutes*60000UL;
    preferences->putUChar("ledBrightness",brightness); preferences->putUChar("ledPattern",pattern);
    preferences->putUChar("idleMinutes",minutes); preferences->putBool("recordSeconds",seconds);
    if(!seconds && rawId) markRaw(rawId,1);
    return true;
  }
  void save(bool force=false) {
    if(!ready) return;
    bool added=false;
    const auto& s=tracker.state;
    for(unsigned i=0;i<s.count;++i) {
      const Session& session=s.sessions[(s.next+SessionLimit-s.count+i)%SessionLimit];
      if(session.id<=lastSummary) continue;
      if(rawId==session.id) closeRaw();
      if(!makeRoom(sizeof(SummaryRecord),false)) { error="Kein Platz für Sitzungsarchiv"; return; }
      SummaryRecord rec{}; rec.session=session; rec.checksum=hashBytes(&rec.session,sizeof(Session));
      File file=LittleFS.open(path("/summaries.bin"),"a");
      const bool ok=file && file.write(reinterpret_cast<uint8_t*>(&rec),sizeof(rec))==sizeof(rec);
      file.flush(); file.close();
      if(!ok) { error="Sitzungsarchiv konnte nicht geschrieben werden"; ready=false; return; }
      ++summaryCount; lastSummary=session.id; added=true;
      if(summaryCount>ArchiveLimit && !compactJournal(1)) { ready=false; return; }
    }
    Store::save(force || added);
  }
  void record(const Sample& sample,uint32_t now) {
    if(!ready) return;
    auto& s=tracker.state;
    if(rawId && (!s.active || rawId!=s.current.id)) closeRaw();
    if(!s.active) { rawPaused=false; return; }
    if(!recordSeconds) { if(rawId) markRaw(rawId,1); closeRaw(); return; }
    if(!rawId) {
      // Persist device and session identity before exposing data in the journal.
      Store::save(true);
      if(!error.isEmpty()) return;
      if(!makeRoom(8192)) { rawPaused=true; return; }
      rawId=s.current.id;
      rawFile=LittleFS.open(rawPath(rawId),"a");
      if(!rawFile) { rawId=0; error="Messdatei konnte nicht geöffnet werden"; return; }
      if(rawFile.size()==0) {
        RawHeader header; header.id=rawId; header.started=s.current.started;
        if(tracker.elapsedMs()>1500) header.flags|=1;
        if(rawFile.write(reinterpret_cast<uint8_t*>(&header),sizeof(header))!=sizeof(header)) {
          closeRaw(); error="Messkopf konnte nicht geschrieben werden"; return;
        }
        rawFile.flush();
      }
    }
    RawRecord record{};
    record.elapsedMs=static_cast<uint32_t>(std::min<uint64_t>(UINT32_MAX,tracker.elapsedMs()));
    record.power=sample.power; record.cadence10=sample.cadence*10; record.speed100=sample.speed*100;
    record.bikeEnergy=sample.energy;record.reserved=static_cast<uint16_t>(sample.resistance);
    record.distanceMeters=std::min<double>(UINT32_MAX,s.current.stats.km*1000);
    record.calories1000=std::min<double>(UINT32_MAX,s.current.stats.kcal*1000);
    record.flags=(sample.fresh?1:0)|(sample.hasPower?2:0)|(sample.hasCadence?4:0)|
                 (sample.hasSpeed?8:0)|(sample.hasEnergy?16:0)|(s.current.stats.estimatedCalories?32:0)|(sample.hasResistance?64:0);
    if(rawPaused && !makeRoom(8192)) return;
    rawPaused=false; rawBuffer[rawBuffered++]=record;
    if(rawBuffered==30 || now-rawFlushedAt>=30000) flushRaw();
  }
  std::vector<RawFile> rawFiles() const {
    std::vector<RawFile> result;
    if(!ready) return result;
    File root=LittleFS.open(rootPrefix.isEmpty()?String("/"):rootPrefix); File f=root.openNextFile();
    while(f) {
      String name=f.name(); name=name.substring(name.lastIndexOf('/')+1); unsigned id=0;
      if((sscanf(name.c_str(),"raw_%u.bin",&id)==1 || sscanf(name.c_str(),"/raw_%u.bin",&id)==1)
          && name.endsWith(".bin") && f.size()>=sizeof(RawHeader)) {
        RawHeader header; f.read(reinterpret_cast<uint8_t*>(&header),sizeof(header));
        if(header.magic==0x31574152 && header.id==id) result.push_back({id,uint32_t(f.size()),header.flags});
      }
      f.close(); f=root.openNextFile();
    }
    root.close(); std::sort(result.begin(),result.end(),[](const RawFile& a,const RawFile& b){return a.id<b.id;});
    return result;
  }
  uint32_t rawSize(uint32_t id) {
    if(!ready || (tracker.state.active && tracker.state.current.id==id)) return 0;
    File f=LittleFS.open(rawPath(id),"r"); return f ? f.size():0;
  }
  size_t readRaw(uint32_t id,uint32_t offset,uint8_t* out,size_t bytes) {
    if(!rawSize(id)) return 0;
    File f=LittleFS.open(rawPath(id),"r"); if(!f.seek(offset)) return 0;
    pinnedRaw=id; pinnedAt=millis();
    return f.read(out,bytes);
  }
  bool acknowledge(uint32_t id,uint32_t bytes,uint32_t checksum) {
    lastAck=id; lastAckOk=false;
    if(!ready || (tracker.state.active && tracker.state.current.id==id)) return false;
    if(rawSize(id)!=bytes || !bytes) return false;
    File f=LittleFS.open(rawPath(id),"r"); uint8_t block[512]; uint32_t hash=2166136261U;
    while(f.available()) { size_t n=f.read(block,sizeof(block)); if(!n) { f.close(); return false; } hash=hashBytes(block,n,hash); }
    f.close();
    if(hash!=checksum) return false;
    lastAckOk=LittleFS.remove(rawPath(id));
    if(lastAckOk && pinnedRaw==id) pinnedRaw=0;
    return lastAckOk;
  }
  String statusJson(bool wifi=false) const {
    auto files=rawFiles(); uint32_t rawBytes=0;
    for(auto& f:files) rawBytes+=f.bytes;
    size_t total=ready?LittleFS.totalBytes():0, used=ready?LittleFS.usedBytes():0;
    String j="{\"total\":"+String(total)+",\"used\":"+String(used)+",\"free\":"+String(total-used);
    j+=",\"reserve\":"+String(FreeReserve)+",\"rawBytes\":"+String(rawBytes);
    j+=",\"rawSessions\":"+String(files.size())+",\"summaries\":"+String(summaryCount);
    j+=",\"summaryLimit\":1000,\"evictedRaw\":"+String(pressureDeletions);
    j+=",\"rawPaused\":"+String(rawPaused?"true":"false")+",\"ledBrightness\":"+String(ledBrightness);
    j+=",\"ledPattern\":"+String(ledPattern)+",\"idleMinutes\":"+String(idleMinutes);
    j+=",\"recordSeconds\":"+String(recordSeconds?"true":"false")+",\"wifi\":"+String(wifi?"true":"false");
    j+=",\"lastAck\":"+String(lastAck)+",\"lastAckOk\":"+String(lastAckOk?"true":"false");
    j+=",\"error\":"+quote(error.c_str())+"}";
    return j;
  }
  String json(uint32_t before=0,bool wifi=false) const {
    const auto& s=tracker.state;
    String j="{\"version\":2,\"archiveId\":"+quote(archiveId.c_str())+",\"error\":"+quote(error.c_str());
    j+=",\"storage\":"+statusJson(wifi)+",\"devices\":[";
    for(unsigned i=0;i<s.deviceCount;++i) {
      if(i) j+=','; auto total=s.devices[i].totals; bool active=s.active&&s.current.device==i;
      if(active) total.add(s.current.stats);
      j+="{\"address\":"+quote(s.devices[i].address)+",\"name\":"+quote(s.devices[i].name);
      j+=",\"sessions\":"+String(s.devices[i].sessions)+",\"active\":"+String(active?"true":"false");
      j+=",\"stats\":"+stats(total)+"}";
    }
    j+="],\"current\":"+(s.active?session(s.current):String("null"))+",\"sessions\":[";
    unsigned included=0; uint32_t nextBefore=0; bool more=false;
    File f=LittleFS.open(path("/summaries.bin"),"r"); SummaryRecord rec;
    for(int i=int(summaryCount)-1;i>=0;--i) {
      if(!f.seek(i*sizeof(rec)) || !readRecord(f,rec)) break;
      if(before && rec.session.id>=before) continue;
      if(included==20) { more=true; break; }
      if(included++) j+=',';
      j+=session(rec.session); nextBefore=rec.session.id;
    }
    f.close();
    j+="],\"hasMore\":"+String(more?"true":"false")+",\"nextBefore\":"+String(nextBefore);
    File imported=LittleFS.open("/mybodytone.json","r");
    j+=",\"importChecksum\":"+String(importChecksum)+",\"importBytes\":"+String(imported?imported.size():0); imported.close();
    j+=",\"rawFiles\":["; bool comma=false;
    for(auto& r:rawFiles()) {
      if(s.active && s.current.id==r.id) continue;
      if(comma) j+=','; comma=true;
      j+="{\"id\":"+String(r.id)+",\"bytes\":"+String(r.bytes)+",\"flags\":"+String(r.flags)+"}";
    }
    j+="],\"limit\":1000,\"deviceLimit\":16}"; return j;
  }
 private:
  Preferences* preferences=nullptr;
  File rawFile; uint32_t rawId=0,rawFlushedAt=0,pinnedRaw=0,pinnedAt=0;
  RawRecord rawBuffer[30]; unsigned rawBuffered=0;
  String rawPath(uint32_t id) const { return rootPrefix+"/raw_"+String(id)+".bin"; }
  static bool readRecord(File& file,SummaryRecord& r) {
    return file && file.read(reinterpret_cast<uint8_t*>(&r),sizeof(r))==sizeof(r) &&
      r.session.id && hashBytes(&r.session,sizeof(Session))==r.checksum;
  }
  bool loadJournal() {
    File f=LittleFS.open(path("/summaries.bin"),"r"); if(!f) return true;
    const size_t bytes=f.size(); SummaryRecord r;
    while(readRecord(f,r)) {
      if(r.session.id<=lastSummary || r.session.device>=DeviceLimit) break;
      ++summaryCount; lastSummary=r.session.id;
    }
    f.close();
    if(bytes && !summaryCount) { error="Sitzungsarchiv beschädigt; Datei bleibt erhalten"; return false; }
    if(bytes!=summaryCount*sizeof(r) || summaryCount>ArchiveLimit)
      return compactJournal(summaryCount>ArchiveLimit?summaryCount-ArchiveLimit:0);
    return true;
  }
  bool compactJournal(unsigned skip) {
    if(!makeRoom((summaryCount-skip)*sizeof(SummaryRecord)+8192,false)) return false;
    File in=LittleFS.open(path("/summaries.bin"),"r"),out=LittleFS.open(path("/summaries.tmp"),"w");
    if(!in || !out) { error="Archivverdichtung fehlgeschlagen"; return false; }
    in.seek(skip*sizeof(SummaryRecord)); SummaryRecord r; bool ok=true;
    for(unsigned i=skip;i<summaryCount;++i) {
      if(!readRecord(in,r) || out.write(reinterpret_cast<uint8_t*>(&r),sizeof(r))!=sizeof(r)) {ok=false;break;}
    }
    out.flush(); out.close(); in.close();
    if(ok) ok=LittleFS.rename(path("/summaries.tmp"),path("/summaries.bin"));
    if(!ok) { error="Archivverdichtung fehlgeschlagen"; return false; }
    summaryCount-=skip; return true;
  }
  bool makeRoom(size_t required,bool reserve=true) {
    if(!ready) return false;
    const size_t target=(reserve?rawReserve:8192)+required;
    if(LittleFS.totalBytes()-LittleFS.usedBytes()>=target) return true;
    for(auto& r:rawFiles()) {
      if(r.id==rawId || (tracker.state.active&&r.id==tracker.state.current.id) ||
         (r.id==pinnedRaw && millis()-pinnedAt<60000)) continue;
      if(LittleFS.remove(rawPath(r.id))) {
        ++pressureDeletions; preferences->putUInt("rawEvicted",pressureDeletions);
      }
      if(LittleFS.totalBytes()-LittleFS.usedBytes()>=target) return true;
    }
    return false;
  }
  void markRaw(uint32_t id,uint16_t flags) {
    File f=LittleFS.open(rawPath(id),"r+"); RawHeader h;
    if(f && f.read(reinterpret_cast<uint8_t*>(&h),sizeof(h))==sizeof(h)) {
      h.flags|=flags; f.seek(0); f.write(reinterpret_cast<uint8_t*>(&h),sizeof(h)); f.flush();
    }
    f.close();
  }
  void flushRaw() {
    if(!rawFile || !rawBuffered) return;
    if(!makeRoom(8192)) {
      rawBuffered=0; rawPaused=true; rawFile.flush(); markRaw(rawId,1); return;
    }
    size_t bytes=rawBuffered*sizeof(RawRecord);
    if(rawFile.write(reinterpret_cast<uint8_t*>(rawBuffer),bytes)!=bytes) {
      error="Messverlauf unvollständig: Schreibfehler"; rawPaused=true; markRaw(rawId,1);
    }
    rawFile.flush(); rawBuffered=0; rawFlushedAt=millis();
  }
  void closeRaw() { flushRaw(); rawFile.close(); rawId=0; rawBuffered=0; }
};
}
