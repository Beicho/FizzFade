package io.github.qishui.automix;
import java.util.Random;
public final class BeatMatchTest {
 private static int checks;
 private static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
 static BeatGrid signal(double bpm,long offset,int millis,int kind) {
  BeatGrid grid=new BeatGrid();Random random=new Random(3409);
  for(int t=0;t<millis;t+=10){
   double phase=BeatGrid.positive(t-offset,60000/bpm);
   double pulse=phase<35?0.65*Math.exp(-phase/13):0;
   double value=kind==0?0.04+pulse+random.nextDouble()*0.015:kind==1?0:kind==2?0.3:0.04+random.nextDouble()*0.35;
   grid.add(value,10,t);
  }
  return grid;
 }
 static BeatGrid pattern(double bpm,long offset,int millis,double scale,double offbeat,double unused) {
  BeatGrid grid=new BeatGrid();Random random=new Random(3409);
  double period=60000/bpm;
  for(int t=0;t<millis;t+=10){
   double phase=BeatGrid.positive(t-offset,period), half=BeatGrid.positive(t-offset-period/2,period);
   double pulse=(phase<35?0.65*Math.exp(-phase/13):0)+(half<35?0.65*offbeat*Math.exp(-half/13):0);
   grid.add(scale*(0.04+pulse+random.nextDouble()*0.015),10,t);
  }
  return grid;
 }
 public static void main(String[] args){
  for(double bpm:new double[]{75,90,100,113,120,128,140,160}) {
   BeatGrid.Result g=signal(bpm,130,14000,0).result();
   System.out.println("tempo="+bpm+" found="+g.bpm+" phase="+g.phaseMs+" confidence="+g.confidence);
   check(g.reliable(),"periodic onset must be reliable "+bpm);
   check(Math.abs(g.bpm-bpm)<0.7,"tempo precision "+bpm);
   double error=BeatGrid.positive(g.phaseMs-130+g.period()/2,g.period())-g.period()/2;
   check(Math.abs(error)<25,"phase precision "+bpm);
  }
  for(int kind:new int[]{1,2,3})check(!signal(120,0,14000,kind).result().reliable(),"reject silence/tone/random "+kind);
  for(double bpm:new double[]{100,116,121,128}) {
   // Syncopation: weaker off-beat 8ths (bass/guitar) must not pull the phase off the kick.
   BeatGrid.Result g=pattern(bpm,130,14000,1,0.25,0).result();
   check(g.reliable(),"syncopated pulse reliable "+bpm);
   check(Math.abs(g.bpm-bpm)<0.7,"syncopated tempo "+bpm);
   double error=BeatGrid.positive(g.phaseMs-130+g.period()/2,g.period())-g.period()/2;
   check(Math.abs(error)<25,"syncopated phase on the kick "+bpm+" error="+error);
   // Quiet intro / fading tail: same rhythm 14 dB down.
   BeatGrid.Result q=pattern(bpm,130,14000,0.2,0,0).result();
   check(q.reliable(),"quiet pulse reliable "+bpm);
   double qe=BeatGrid.positive(q.phaseMs-130+q.period()/2,q.period())-q.period()/2;
   check(Math.abs(qe)<25,"quiet pulse phase "+bpm);
  }
  BeatGrid.Result even=pattern(120,130,14000,1,1,0).result();
  check(!even.reliable() || Math.abs(BeatGrid.positive(even.phaseMs-130+125,250)-125)<25,"equal 8ths never yield an off-grid phase");
  BeatGrid quietNoise=new BeatGrid();Random r=new Random(77);
  for(int t=0;t<14000;t+=10)quietNoise.add(0.01+r.nextDouble()*0.08,10,t);
  check(!quietNoise.result().reliable(),"boosted quiet noise still rejected");
  check(!signal(120,0,3000,0).result().reliable(),"reject insufficient evidence");
  BeatGrid.Result out=new BeatGrid.Result(120,90,0.95,12000),in=new BeatGrid.Result(116,150,0.95,12000);
  for(int old=100000;old<101000;old+=17) {
   BeatMatch plan=BeatMatch.plan(out,in,old,200);
   check(plan!=null && Math.abs(plan.rate-120d/116)<1e-8,"bounded speed");
   double residual=BeatGrid.positive(in.untilBeat(plan.targetPositionMs)/plan.rate-out.untilBeat(old)+250,500)-250;
   check(Math.abs(residual)<0.001,"aligned beat phase");
  }
  check(BeatMatch.plan(out,new BeatGrid.Result(90,0,0.99,12000),0,0)==null,"reject unrelated tempo");
  check(BeatMatch.plan(BeatGrid.Result.NONE,in,0,0)==null,"never invent missing BPM");
  check(BeatMatch.recovery(1.06,0,5000)==1.06f && BeatMatch.recovery(1.06,5000,5000)==1,"restore rate endpoints");
  float prior=1.06f;
  for(int t=0;t<6000;t+=40){float v=BeatMatch.recovery(1.06,t,5000);check(v<=prior && v>=1,"smooth monotonic recovery");prior=v;}
  BeatGrid reset=signal(120,0,14000,0);reset.add(0.2,10,90000);check(!reset.result().reliable(),"seek invalidates timeline");
  System.out.println("PASS "+checks+" beat matching assertions");
 }
}
