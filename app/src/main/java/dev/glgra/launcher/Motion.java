package dev.glgra.launcher;

import android.animation.ValueAnimator;
import android.view.Choreographer;
import android.view.animation.PathInterpolator;
import java.util.function.Consumer;

/** A critically damped, interruptible spatial spring; callbacks exist only while moving. */
final class Motion {
    static final android.animation.TimeInterpolator EFFECTS=new PathInterpolator(.2f,0,0,1);
    static final class Spring implements Choreographer.FrameCallback {
        private final Consumer<Float> update; private final Runnable settled;
        private final Choreographer clock=Choreographer.getInstance();
        private float value,velocity,target; private long previous; private boolean running;
        Spring(float value,Consumer<Float> update,Runnable settled){this.value=value;this.target=value;this.update=update;this.settled=settled;}
        void to(float target){this.target=target;if(!ValueAnimator.areAnimatorsEnabled()){cancel();value=target;velocity=0;update.accept(value);settled.run();return;}if(!running){running=true;previous=0;clock.postFrameCallback(this);}}
        void cancel(){running=false;previous=0;clock.removeFrameCallback(this);}
        @Override public void doFrame(long frame){
            if(!running)return;
            float dt=previous==0?1f/60:Math.min(.064f,(frame-previous)/1_000_000_000f);previous=frame;
            float scale=ValueAnimator.getDurationScale();if(scale<=0){value=target;velocity=0;}else{
                dt/=scale;float omega=24,offset=value-target,c=velocity+omega*offset,e=(float)Math.exp(-omega*dt);
                value=target+(offset+c*dt)*e;velocity=(velocity-omega*c*dt)*e;
            }
            if(Math.abs(value-target)<.0005f&&Math.abs(velocity)<.01f){value=target;velocity=0;running=false;update.accept(value);settled.run();}
            else{update.accept(value);if(running)clock.postFrameCallback(this);}
        }
    }
}
