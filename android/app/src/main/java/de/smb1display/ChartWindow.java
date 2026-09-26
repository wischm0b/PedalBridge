package de.smb1display;

/** Calendar-day viewport, independent of display density and Android gestures. */
final class ChartWindow {
    final double lower, upper, defaultStart;
    double start, end;
    ChartWindow(double earliest, double latest) {
        java.time.LocalDate last=java.time.LocalDate.ofEpochDay((long)Math.floor(latest));
        upper=last.plusDays(1).toEpochDay();
        defaultStart=last.plusDays(1).minusMonths(3).toEpochDay();
        lower=Math.min(Math.floor(earliest),defaultStart);reset();
    }
    void reset(){set(defaultStart,upper);}
    void all(){set(lower,upper);}
    double span(){return end-start;}
    void set(double from,double to){
        if(!Double.isFinite(from) || !Double.isFinite(to) || to<=from)return;
        double width=Math.max(1,Math.min(upper-lower,to-from));
        start=Math.max(lower,Math.min(upper-width,from));end=start+width;
    }
    void pan(double fraction){set(start+span()*fraction,end+span()*fraction);}
    void transform(double factor,double previousFocus,double currentFocus){
        if(!Double.isFinite(factor) || factor<=0)return;
        double width=Math.max(1,Math.min(upper-lower,span()/factor));
        double anchor=start+span()*previousFocus;
        set(anchor-width*currentFocus,anchor+width*(1-currentFocus));
    }
    double fraction(double day){return (day-start)/span();}
}
