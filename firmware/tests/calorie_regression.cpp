#include "history_model.h"
#include "history_selftest.h"
#include <cmath>
#include <cstdio>
#include <memory>

using history::Sample;
using history::Tracker;
static Sample moving(uint16_t energy=100) {
  Sample s; s.fresh=true; s.hasPower=s.hasCadence=true;
  s.power=200; s.cadence=80; s.hasEnergy=true; s.energy=energy; return s;
}
static std::unique_ptr<Tracker> tracker() {
  std::unique_ptr<Tracker> t(new Tracker);t->device("02:00:00:00:00:02","Synthetic bike");return t;
}
static bool near(double a,double b){return std::abs(a-b)<0.000001;}
static int failures=0;
static void check(const char* name,bool result){std::printf("%s: %s\n",result?"PASS":"FAIL",name);if(!result)++failures;}
int main(){
  {
    auto t=tracker();Sample s=moving(0);t->tick(0,s,1000);
    for(unsigned i=1;i<=1800;++i){s.energy=i/6;t->tick(0,s,1000+i*1000);}
    double before=t->state.current.stats.kcal;s.energy=0;t->tick(0,s,1802000);s.energy=300;t->tick(0,s,1803000);
    std::printf("Temporary counter reset: %.0f -> %.0f kcal\n",before,t->state.current.stats.kcal);
    check("300 -> 0 -> 300 must not double the workout",near(before,300)&&near(t->state.current.stats.kcal,300));
    s.energy=301;t->tick(0,s,1804000);check("counting resumes normally after rejected rebound",near(t->state.current.stats.kcal,301));
  }
  {
    auto t=tracker();Sample s=moving();t->tick(0,s,1000);s.energy=101;t->tick(0,s,2000);s.energy=401;t->tick(0,s,3000);
    check("sudden 300 kcal increase is rejected",near(t->state.current.stats.kcal,1));s.energy=402;t->tick(0,s,4000);check("a spike cannot poison subsequent increments",near(t->state.current.stats.kcal,2));
  }
  {
    auto t=tracker();Sample s=moving();t->tick(0,s,1000);s.energy=102;t->tick(0,s,2000);s.energy=0;t->tick(0,s,3000);s.energy=1;t->tick(0,s,4000);
    check("a genuine reset establishes a new baseline",near(t->state.current.stats.kcal,3));
  }
  {
    auto t=tracker();Sample s=moving(0);t->tick(0,s,1000);for(unsigned i=1;i<=30;++i){s.energy=i==30?30:0;t->tick(0,s,1000+i*1000);}
    check("legitimate delayed counter updates are retained",near(t->state.current.stats.kcal,30));
  }
  {
    auto t=tracker();Sample s=moving();t->tick(0,s,1000);s.energy=101;t->tick(0,s,2000);Sample missing;t->tick(0,missing,3000);s.energy=350;t->tick(0,s,10000);s.energy=351;t->tick(0,s,11000);
    check("disconnects do not add unobserved counter history",near(t->state.current.stats.kcal,2));
  }
  {
    auto t=tracker();Sample s=moving();t->tick(0,s,1000);s.energy=160;t->tick(0,s,61000);s.energy=161;t->tick(0,s,62000);
    check("blocked sampling intervals are not backfilled",near(t->state.current.stats.kcal,1));
  }
  {
    auto t=tracker();Sample s=moving();t->tick(0,s,1000);s.energy=101;t->tick(0,s,2000);s.hasEnergy=false;t->tick(0,s,3000);t->tick(0,s,4000);s.hasEnergy=true;s.energy=105;t->tick(0,s,5000);s.energy=106;t->tick(0,s,6000);
    check("power fallback and returning counter cannot overlap",near(t->state.current.stats.kcal,2+400/(4184.0*.24)));
  }
  {
    auto t=tracker();Sample s=moving();t->tick(0,s,1000);s.energy=UINT16_MAX;t->tick(0,s,2000);s.energy=100;t->tick(0,s,3000);s.energy=101;t->tick(0,s,4000);
    check("FTMS unavailable energy uses power fallback",near(t->state.current.stats.kcal,1+200/(4184.0*.24))&&t->state.current.stats.estimatedCalories);
  }
  {
    auto t=tracker();Sample s=moving();t->tick(0,s,1000);s.energy=101;t->tick(0,s,2000);s.cadence=0;s.energy=150;t->tick(0,s,3000);s.cadence=80;s.energy=200;t->tick(0,s,4000);s.energy=201;t->tick(0,s,5000);
    check("pauses cannot charge a counter catch-up on resume",near(t->state.current.stats.kcal,2));
  }
  {
    auto t=tracker();Sample s=moving();s.hasEnergy=false;s.power=361.7f;t->tick(0,s,1000);for(unsigned i=1;i<=2040;++i)t->tick(0,s,1000+i*1000);
    double expected=static_cast<double>(s.power)*2040/(4184.0*.24);std::printf("34 minutes at 361.7 W: %.2f kcal\n",t->state.current.stats.kcal);
    check("power-only session integrates actual riding time",near(t->state.current.stats.kcal,expected));
  }
  check("existing lifecycle, rollover and archive model regressions",historySelfTest());
  std::printf("Calorie regression result: %d failure(s)\n",failures);return failures?1:0;
}
