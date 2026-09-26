#pragma once
#include "training_archive.h"
#include <memory>

inline bool archiveSelfTest() {
  using namespace history;
  const String testDir="/archivetest";
  auto cleanup=[&](){
    File root=LittleFS.open(testDir); if(root) {
      File f=root.openNextFile();
      while(f) {String name=f.name();name=name.substring(name.lastIndexOf('/')+1);f.close();
        LittleFS.remove(testDir+"/"+name);f=root.openNextFile();}
      root.close(); LittleFS.rmdir(testDir);
    }
  };
  cleanup();
  Preferences prefs; prefs.begin("archivetest",false);prefs.clear();
  Sample sample;sample.fresh=sample.hasPower=sample.hasCadence=sample.hasSpeed=true;
  sample.power=200;sample.cadence=90;sample.speed=36;
  uint32_t now=1000;
  { // Existing 0.10.0 snapshots must migrate without losing IDs or totals.
    std::unique_ptr<Store> legacy(new Store);legacy->rootPrefix=testDir;legacy->begin(prefs);
    if(!legacy->ready) return false;
    int device=legacy->tracker.device("02:00:00:00:00:02","Test bike");
    for(int i=0;i<10;++i) {legacy->tracker.tick(device,sample,now);legacy->tracker.tick(device,sample,now+1000);legacy->tracker.finish(1);now+=2000;}
    legacy->save(true);
  }
  std::unique_ptr<Archive> archive(new Archive);archive->rootPrefix=testDir;archive->begin(prefs);
  if(!archive->ready || archive->summaryCount!=10 || archive->tracker.state.devices[0].sessions!=10) return false;
  for(int i=0;i<1000;++i) {
    archive->tracker.tick(0,sample,now);archive->tracker.tick(0,sample,now+1000);archive->tracker.finish(1);now+=2000;
    if(i%100==99) {archive->save(true);Serial.printf("Archive test: %d generated, retained %u, error %s\n",i+1,archive->summaryCount,archive->error.c_str());delay(1);}
  }
  if(archive->summaryCount!=1000 || archive->tracker.state.devices[0].sessions!=1010 || !archive->error.isEmpty()) return false;
  Serial.println("Archive test: paging");
  String page=archive->json();
  if(page.indexOf("\"id\":1010,")<0 || page.indexOf("\"hasMore\":true")<0 || page.indexOf("\"nextBefore\":991")<0) return false;
  // Recover a partial append, then a complete journal record ahead of checkpoint.
  {File f=LittleFS.open(testDir+"/summaries.bin","a");uint8_t tail[3]={1,2,3};f.write(tail,3);f.close();}
  archive.reset(new Archive);archive->rootPrefix=testDir;archive->begin(prefs);
  if(!archive->ready || archive->summaryCount!=1000) return false;
  SummaryRecord ahead{};ahead.session.id=1011;ahead.session.device=0;ahead.session.reason=2;
  ahead.session.stats.km=1; ahead.checksum=hashBytes(&ahead.session,sizeof(Session));
  {File f=LittleFS.open(testDir+"/summaries.bin","a");f.write(reinterpret_cast<uint8_t*>(&ahead),sizeof(ahead));f.close();}
  archive.reset(new Archive);archive->rootPrefix=testDir;archive->begin(prefs);
  if(archive->tracker.state.devices[0].sessions!=1011 || archive->tracker.state.nextId!=1012) return false;
  archive.reset(new Archive);archive->rootPrefix=testDir;archive->begin(prefs);
  if(archive->tracker.state.devices[0].sessions!=1011) return false;
  Serial.println("Archive test: journal recovery passed");
  // Write a real binary recording in the isolated directory.
  for(int i=0;i<35;++i) {archive->tracker.tick(0,sample,now);archive->record(sample,now);now+=1000;}
  uint32_t id=archive->tracker.state.current.id;
  if(archive->acknowledge(id,16+35*24,0)) return false; // Cannot remove a running session.
  archive->tracker.finish(1);archive->save(true);
  uint32_t bytes=archive->rawSize(id);
  if(bytes!=16+35*24) return false;
  uint8_t chunk[500];uint32_t hash=2166136261U,offset=0;
  while(offset<bytes) {size_t n=archive->readRaw(id,offset,chunk,sizeof(chunk));if(!n)return false;hash=hashBytes(chunk,n,hash);offset+=n;}
  if(archive->acknowledge(id,bytes,hash^1) || !archive->rawSize(id)) return false;
  if(!archive->acknowledge(id,bytes,hash) || archive->rawSize(id)) return false;
  Serial.println("Archive test: raw ACK passed");
  // Pressure evicts an old completed recording, never the current one or totals.
  for(int i=0;i<31;++i) {archive->tracker.tick(0,sample,now);archive->record(sample,now);now+=1000;}
  uint32_t old=archive->tracker.state.current.id;archive->tracker.finish(1);archive->save(true);
  const uint32_t totalBefore=archive->tracker.state.devices[0].sessions;
  archive->tracker.tick(0,sample,now);archive->record(sample,now);now+=1000;
  archive->rawReserve=LittleFS.totalBytes()-LittleFS.usedBytes();
  for(int i=0;i<31;++i) {archive->tracker.tick(0,sample,now);archive->record(sample,now);now+=1000;}
  bool pressureOk=archive->pressureDeletions>0 && !archive->rawSize(old) && archive->tracker.state.active &&
      archive->tracker.state.devices[0].sessions==totalBefore;
  archive->rawReserve=FreeReserve;archive->tracker.finish(1);archive->save(true);
  archive.reset();cleanup();prefs.clear();prefs.end();
  return pressureOk;
}
