#pragma once
#include <algorithm>
#include <cstdint>
#include <cstring>

namespace history {
constexpr unsigned DeviceLimit = 16, SessionLimit = 128;
constexpr uint32_t IdleMs = 300000;
struct Stats {
  double activeMs = 0, elapsedMs = 0, km = 0, kcal = 0;
  double wattMs = 0, cadenceMs = 0, speedMs = 0;
  double powerTime = 0, cadenceTime = 0, speedTime = 0;
  float maxPower = 0, maxCadence = 0, maxSpeed = 0;
  bool estimatedCalories = false;
  void add(const Stats& s) {
    activeMs += s.activeMs; elapsedMs += s.elapsedMs; km += s.km; kcal += s.kcal;
    wattMs += s.wattMs; cadenceMs += s.cadenceMs; speedMs += s.speedMs;
    powerTime += s.powerTime; cadenceTime += s.cadenceTime; speedTime += s.speedTime;
    maxPower = std::max(maxPower,s.maxPower);
    maxCadence = std::max(maxCadence,s.maxCadence);
    maxSpeed = std::max(maxSpeed,s.maxSpeed);
    estimatedCalories |= s.estimatedCalories;
  }
};
struct Device { char address[18] = {}; char name[48] = {}; uint32_t sessions = 0; Stats totals; };
struct Session {
  uint32_t id = 0, started = 0;
  uint8_t device = 0, reason = 0; // 0 active, 1 idle, 2 manual, 3 restart, 4 bike switch
  Stats stats;
};
struct State {
  uint32_t magic = 0x48495331, version = 1, generation = 0, nextId = 1;
  uint16_t deviceCount = 0, count = 0, next = 0;
  bool active = false;
  Device devices[DeviceLimit];
  Session sessions[SessionLimit], current;
};
struct Sample {
  float power = 0, cadence = 0, speed = 0;
  bool hasPower = false, hasCadence = false, hasSpeed = false, fresh = false;
  bool hasEnergy = false;
  uint16_t energy = 0;
  int16_t resistance=0; bool hasResistance=false;
};
class Tracker {
 public:
  State state;
  bool dirty = false;
  uint32_t idleTimeoutMs = IdleMs;
  uint64_t elapsedMs() const { return ageMs; }
  bool waitingForStop() const { return waitForStop; }
  void restoreFinished(const Session& session) {
    auto& d=state.devices[session.device]; d.totals.add(session.stats); ++d.sessions;
    state.sessions[state.next]=session; state.next=(state.next+1)%SessionLimit;
    state.count=std::min<unsigned>(SessionLimit,state.count+1);
    state.nextId=std::max(state.nextId,session.id+1);
    if(state.active && state.current.id==session.id) state.active=false;
    dirty=true;
  }
  int device(const char* address, const char* name) {
    for (unsigned i=0;i<state.deviceCount;++i) {
      if (!std::strcmp(state.devices[i].address,address)) return i;
    }
    if (state.deviceCount == DeviceLimit) return -1;
    auto& d = state.devices[state.deviceCount];
    std::strncpy(d.address,address,sizeof(d.address)-1);
    std::strncpy(d.name,name,sizeof(d.name)-1);
    dirty = true;
    return state.deviceCount++;
  }
  void finish(uint8_t reason) {
    if (reason == 2) { waitForStop = true; stationary = false; }
    if (!state.active) return;
    state.current.reason = reason;
    auto& d = state.devices[state.current.device];
    d.totals.add(state.current.stats); ++d.sessions;
    state.sessions[state.next] = state.current;
    state.next = (state.next+1)%SessionLimit;
    state.count = std::min<unsigned>(SessionLimit,state.count+1);
    state.active = false; dirty = true;
    previousMoving = false; energyValid = false;
  }
  void setTime(uint32_t epoch, uint32_t now) {
    clockEpoch = epoch; clockMs = now; clockRemainder = 0;
    if (state.active && !state.current.started) {
      state.current.started = epoch - static_cast<uint32_t>(ageMs/1000);
      dirty = true;
    }
  }
  void tick(int deviceIndex, const Sample& sample, uint32_t now) {
    const uint32_t dt = haveTick ? now-lastTick : 0;
    lastTick = now; haveTick = true;
    if (clockEpoch) { const uint32_t elapsed=now-clockMs; clockEpoch += elapsed/1000;
      clockRemainder += elapsed%1000; clockEpoch += clockRemainder/1000;
      clockRemainder %= 1000; clockMs = now; }
    if (state.active && deviceIndex >= 0 && state.current.device != deviceIndex) finish(4);
    if (state.active && now-lastMoving >= idleTimeoutMs) finish(1);
    // Cadence proves pedaling; residual flywheel speed/power must not start a ride.
    const bool hasMotion = sample.hasCadence || sample.hasPower || sample.hasSpeed;
    const bool moving = sample.fresh && (sample.hasCadence ? sample.cadence > 0 :
        sample.hasPower ? sample.power > 0 : sample.hasSpeed && sample.speed > 0);
    if (waitForStop) {
      if (sample.fresh && hasMotion && !moving) {
        if (!stationary || dt > 5000) { stationary = true; stationarySince = now; }
        if (now-stationarySince >= 2000) { waitForStop = false; stationary = false; }
      } else stationary = false;
    }
    if (moving && !state.active && !waitForStop && deviceIndex >= 0) {
      state.current = Session{}; state.current.device = deviceIndex;
      state.current.id = state.nextId++; state.current.started = clockEpoch;
      state.active = true; ageMs = 0; previousMoving = false; energyValid = false;
    } else if (state.active) ageMs += dt;
    if (!state.active) return;
    if (moving) {
      lastMoving = now;
      auto& s = state.current.stats;
      s.elapsedMs = ageMs;
      // Never integrate a stale sample or a long blocked/disconnected interval.
      const double interval = previousMoving && dt<=5000 ? dt : 0;
      s.activeMs += interval;
      if (sample.hasPower) {
        s.powerTime += interval; s.wattMs += sample.power*interval;
        s.maxPower = std::max(s.maxPower,sample.power);
      }
      if (sample.hasCadence) {
        s.cadenceTime += interval; s.cadenceMs += sample.cadence*interval;
        s.maxCadence = std::max(s.maxCadence,sample.cadence);
      }
      if (sample.hasSpeed) {
        s.speedTime += interval; s.speedMs += sample.speed*interval;
        s.km += sample.speed*interval/3600000.0;
        s.maxSpeed = std::max(s.maxSpeed,sample.speed);
      }
      if (sample.hasEnergy) {
        if (energyValid && sample.energy>=lastEnergy) s.kcal += sample.energy-lastEnergy;
        lastEnergy = sample.energy; energyValid = true;
      } else {
        energyValid = false;
        if (sample.hasPower) { s.kcal += sample.power*interval/(1000*4184.0*0.24); s.estimatedCalories = true; }
      }
      dirty = true;
    }
    previousMoving = moving;
    if (!sample.fresh) energyValid = false;
  }
 private:
  uint32_t lastTick = 0, lastMoving = 0, clockEpoch = 0, clockMs = 0, clockRemainder = 0;
  uint64_t ageMs = 0;
  bool haveTick = false, previousMoving = false, energyValid = false;
  bool waitForStop = false, stationary = false;
  uint32_t stationarySince = 0;
  uint16_t lastEnergy = 0;
};
}
