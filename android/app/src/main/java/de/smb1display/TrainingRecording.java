package de.smb1display;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decoder for the existing ESP RAW1 format; no fabricated values for missing signals. */
final class TrainingRecording {
    static final String[] UNITS={"W","rpm","km/h","","kcal","km"};
    final long id;
    final int flags;
    final double[] seconds;
    final double[][] values;
    TrainingRecording(byte[] bytes,long expectedId) {
        if(bytes.length<16 || (bytes.length-16)%24!=0)throw new IllegalArgumentException("Incomplete recording");
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(b.getInt()!=0x31574152)throw new IllegalArgumentException("Unknown recording format");
        id=Integer.toUnsignedLong(b.getInt());b.getInt();
        if(id!=expectedId || Short.toUnsignedInt(b.getShort())!=24)throw new IllegalArgumentException("Recording identity mismatch");
        flags=Short.toUnsignedInt(b.getShort());
        int count=(bytes.length-16)/24;seconds=new double[count];values=new double[count][6];
        for(int i=0;i<count;i++) {
            seconds[i]=Integer.toUnsignedLong(b.getInt())/1000.0;
            if(i>0 && seconds[i]<seconds[i-1])throw new IllegalArgumentException("Invalid recording time");
            int power=b.getShort(),cadence=Short.toUnsignedInt(b.getShort()),speed=Short.toUnsignedInt(b.getShort());b.getShort();
            double distance=Integer.toUnsignedLong(b.getInt())/1000.0,calories=Integer.toUnsignedLong(b.getInt())/1000.0;
            int present=Short.toUnsignedInt(b.getShort()),level=b.getShort();
            boolean fresh=(present&1)!=0;
            values[i][0]=fresh&&(present&2)!=0&&power>=0?power:Double.NaN;
            values[i][1]=fresh&&(present&4)!=0?cadence/10.0:Double.NaN;
            values[i][2]=fresh&&(present&8)!=0?speed/100.0:Double.NaN;
            values[i][3]=fresh&&(present&64)!=0?level:Double.NaN;
            values[i][4]=calories;values[i][5]=distance;
        }
    }
    boolean available(int metric){for(double[] v:values)if(Double.isFinite(v[metric]))return true;return false;}
    static final class Window {
        final double upper;double start,end;
        Window(double duration){upper=Math.max(1,duration);reset();}
        void reset(){start=0;end=upper;}
        double span(){return end-start;}
        void set(double from,double width){width=Math.max(Math.min(10,upper),Math.min(upper,width));start=Math.max(0,Math.min(upper-width,from));end=start+width;}
        void pan(double fraction){set(start+span()*fraction,span());}
        void zoom(double factor,double oldFocus,double newFocus){if(!Double.isFinite(factor)||factor<=0)return;double width=span()/factor; width=Math.max(Math.min(10,upper),Math.min(upper,width));set(start+span()*oldFocus-width*newFocus,width);}
    }
}
