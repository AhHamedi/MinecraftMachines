package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.JsonElement;
import dev.ahmedhamedi.minecraft_machines.content.training.morphology.LeaperPredatorPlanner;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Frozen small native hill suite: physical fixtures, real navigation, no leap/state injection. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperHillAssistTests {
    static final String MODE="predator-hill-assist";
    static final String PROBE_MODE="predator-hill-planner-probe";
    static final String WIDE_MODE="predator-hill-wide-stairs";
    static final int RADIUS=48, SETTLE=80, STOP=80;
    record Spec(String name,int kind,double angle,String pace){}
    static final List<Spec> CASES=List.of(
        new Spec("abrupt2-horrific",0,0,"horrific"),
        new Spec("abrupt2-careful-diagonal",0,.55,"careful"),
        new Spec("three-2rise-1run-horrific",1,0,"horrific"),
        new Spec("three-2rise-1run-careful-diagonal",1,-.55,"careful"),
        new Spec("one-thick-two-tall-ridge-horrific",2,0,"horrific"),
        new Spec("one-thick-two-tall-ridge-careful-diagonal",2,.55,"careful"),
        new Spec("flat-arrival-stop",3,0,"horrific"),
        new Spec("native-gap-freefall-assist-suppression",4,0,"horrific"));
    @GameTest(template="empty",timeoutTicks=11500,batch="leaperHillAssist")
    public static void hills(GameTestHelper helper)throws Exception{
        if(!Set.of(MODE,PROBE_MODE,WIDE_MODE).contains(MinecraftMachinesLeaperLearning.mode())){helper.succeed();return;}
        var run=new Run(helper);try{run.begin();}catch(Exception e){run.close();throw e;}
    }
    static void require(boolean b,String s){if(!b)throw new IllegalStateException(s);}
    static double[] xyz(Vector3d v){return new double[]{v.x,v.y,v.z};}
    static double horizontal(Vector3d a,Vector3d b){return Math.hypot(a.x-b.x,a.z-b.z);}
    static double metric(Object owner,String field){var value=MinecraftMachinesLeaperPursuitRegressionTests.optionalField(owner,field);return value instanceof Number n?n.doubleValue():0;}
    static Map<String,Object> criteria(){
        var c=new LinkedHashMap<String,Object>();
        c.put("version","hill-v1-before-candidate");c.put("arrival_budget_seconds_horrific",30);c.put("arrival_budget_seconds_careful",60);
        c.put("arrival_radius",2.5);c.put("arrival_consecutive_samples",40);c.put("arrival_horizontal_speed_max",.75);c.put("arrival_vertical_speed_max",.75);
        c.put("whole_rig_crossing","All four actual toe centers beyond the last obstacle face; no sideways detour outside six-block route corridor");
        c.put("maximum_backward_travel_blocks",6);c.put("maximum_single_setback_blocks",3);c.put("maximum_reverse_bursts_over4bps",3);
        c.put("desired_quality_gate",Map.of("maximum_backward_travel",3,"maximum_setback",1.5,"maximum_reverse_bursts_over4bps",1,"no_fall_or_inversion",true));
        c.put("maximum_anchor_error",.12);c.put("maximum_limit_violation",.15);c.put("minimum_upright",.45);
        c.put("stop_samples",STOP);c.put("maximum_stop_drift",1.5);c.put("leap_impulses_allowed",0);
        c.put("prospective_climb_delta_caps",Map.of("horizontal",.6,"energy_reducing_braking_horizontal",3,"up",1.25,"down",.7,"applications_per_control_tick",1));
        c.put("freefall","Remove real 25x25 floor patch after walking starts. Require40 samples below original floor minus2 with descending vy<-3 and zero support, with no new assists; permits bounded early support-loss grace.");
        c.put("scoring","Project regression gates, no external/world-class score. Failures and all traces retained; no candidate tuning on these before baseline.");
        return c;
    }
    static final class Trial{
        final Run run;final int id;final Spec spec;
        final List<String> failures=new ArrayList<>(),nonfinite=new ArrayList<>();
        final List<JsonElement> history=new ArrayList<>();final List<Object> inputs=new ArrayList<>();
        final Map<String,Object> metadata=new LinkedHashMap<>();
        final List<Object> stanceProbe=new ArrayList<>();
        MinecraftMachinesLeaper rig;MinecraftMachinesLeaperPredatorNavigator nav;
        Vector3d origin,goal,previous,stopPosition;
        int tick,arrivalRun,arrivalTick=-1,crossTick=-1,stopTick=-1,stopCount,removedTick=-1,freefallSamples;
        int bursts,lastAssist,assistBeforeFall=-1,verifiedImpactBrakes;double climbBeforeFall=-1,stopClimb;boolean reverseBurst,done,success,closed,physicalFailure;
        double backward,maxSetback,peakProgress,maxReverse,anchor,limit,minUp=1,stopDrift,maxSide,error,maxStepMillis;
        double stallReference;int stallTick=SETTLE;double longestStall;
        Trial(Run r,int i){run=r;id=i;var original=CASES.get(i);spec=r.wideStairs?new Spec(original.name.replace("1run","2run"),original.kind,original.angle,original.pace):original;}
        int budget(){return spec.pace.equals("careful")?1200:600;}
        int floor(int d){return switch(spec.kind){case 0->d>=12?9:7;case 1->7+2*Math.clamp(run.wideStairs?(d<12?0:1+(d-12)/2):d-11,0,3);case 2->d==12?9:7;default->7;};}
        double progress(Vector3d p){return (p.x-origin.x)*Math.sin(spec.angle)-(p.z-origin.z)*Math.cos(spec.angle);}
        void initialize(){try{
            var level=run.helper.getLevel();origin=MinecraftMachinesLeaperLearning.arena(level,20+id,Blocks.SMOOTH_STONE,RADIUS);
            int ox=(int)Math.floor(origin.x),oz=(int)Math.floor(origin.z);
            for(int x=ox-RADIUS;x<=ox+RADIUS;x++)for(int z=oz-RADIUS;z<=oz+RADIUS;z++)for(int y=7;y<=21;y++){
                var b=new BlockPos(x,y,z);var desired=y<floor(oz-z)?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState();
                if(!level.getBlockState(b).equals(desired))level.setBlockAndUpdate(b,desired);
            }
            goal=new Vector3d(origin.x+36*Math.sin(spec.angle),spec.kind==0?9:spec.kind==1?13:7,origin.z-36*Math.cos(spec.angle));
            rig=MinecraftMachinesLeaper.spawn(level,origin);rig.reset(0);nav=new MinecraftMachinesLeaperPredatorNavigator(rig,level,run.policy);nav.pace=spec.pace;
            require(!nav.leap.enabled && nav.control.agile,"Walking-only/agile configuration required");
            require(Math.abs(nav.map.height(goal.x,goal.z,goal.y+.55,72)-goal.y)<1e-6,"Goal must be actual exposed floor");
            var field=MinecraftMachinesLeaper.class.getDeclaredField("bodies");field.setAccessible(true);
            @SuppressWarnings("unchecked") var bodies=(List<ServerSubLevel>)field.get(rig);require(bodies.size()==13,"Expected13 bodies");
            var masses=new ArrayList<Double>();double mass=0;
            for(int b=0;b<bodies.size();b++){double m=bodies.get(b).getMassTracker().getMass();double wanted=b==0?27:new double[]{1,8,6}[(b-1)%3];require(Math.abs(m-wanted)<1e-6,"Nonstandard body mass");masses.add(m);mass+=m;}
            require(Math.abs(mass-87)<1e-6,"Require87mass");
            metadata.put("body_masses",masses);metadata.put("settings",MinecraftMachinesLeaperHighStepSettings.effective(nav.control));metadata.put("gravity",xyz(rig.gravity()));
            metadata.put("climb_assist_api_available",MinecraftMachinesLeaperPursuitRegressionTests.hasField(rig,"climbAssistImpulses"));
            var climb=MinecraftMachinesLeaperPursuitRegressionTests.optionalField(nav,"climbAssist");
            metadata.put("climb_assist_requested",Boolean.parseBoolean(System.getProperty("minecraft_machines.leaperClimbAssist","true")));
            metadata.put("climb_assist_effective_enabled",climb==null?null:MinecraftMachinesLeaperPursuitRegressionTests.optionalField(climb,"enabled"));
            metadata.put("origin",xyz(origin));metadata.put("goal",xyz(goal));metadata.put("world_axis_obstacle_z",run.wideStairs?new int[]{oz-12,oz-14,oz-16}:new int[]{oz-12,oz-13,oz-14});metadata.put("stair_run_blocks",run.wideStairs?2:1);
            metadata.put("ridge_thickness_world_blocks",spec.kind==2?1:0);metadata.put("stair_rises",spec.kind==1?3:1);metadata.put("arena_radius",RADIUS);
            previous=rig.corePivot();
            if(run.probeOnly)probeStairs();
        }catch(Exception e){fail("fixture: "+e,false);}}
        Object supportRecord(Object support)throws Exception{
            var out=new LinkedHashMap<String,Object>();
            for(String name:new String[]{"valid","floor","gx","gz","cost"}){
                var f=support.getClass().getDeclaredField(name);f.setAccessible(true);Object value=f.get(support);
                out.put(name,value instanceof Double d && !Double.isFinite(d)?"unavailable":value);
            }return out;
        }
        void probeStairs()throws Exception{
            var planner=nav.planner(goal.y);var field=LeaperPredatorPlanner.class.getDeclaredField("terrain");field.setAccessible(true);
            var actual=(LeaperPredatorPlanner.Terrain)field.get(planner);
            var measure=LeaperPredatorPlanner.class.getDeclaredMethod("measure",double.class,double.class,double.class);measure.setAccessible(true);
            for(double progress=8;progress<=17.00001;progress+=.5){
                double x=origin.x+progress*Math.sin(spec.angle),z=origin.z-progress*Math.cos(spec.angle),floor=actual.height(x,z);
                var bodyCalls=new ArrayList<Object>();
                var wrapped=new LeaperPredatorPlanner.Terrain(){
                    public double height(double x,double z){return actual.height(x,z);}
                    public List<Double> surfaces(double x,double z,double low,double high){return actual.surfaces(x,z,low,high);}
                    public double chassisStepClearance(){return actual.chassisStepClearance();}
                    public boolean adaptiveStanceSampling(){return actual.adaptiveStanceSampling();}
                    public double extraCost(double x,double y,double z){return actual.extraCost(x,y,z);}
                    public boolean bodyClear(double x,double y,double z){return bodyClear(x,y,z,0,0);}
                    public boolean bodyClear(double x,double y,double z,double gx,double gz){
                        boolean clear=actual.bodyClear(x,y,z,gx,gz);var hits=new ArrayList<Object>();double height=run.policy.value[4];
                        var box=new AABB(x-1.35,y+height-1.25,z-1.35,x+1.35,y+height+1.4,z+1.35);
                        for(int bx=(int)Math.floor(box.minX);bx<=Math.floor(box.maxX);bx++)for(int by=(int)Math.floor(box.minY);by<=Math.floor(box.maxY);by++)for(int bz=(int)Math.floor(box.minZ);bz<=Math.floor(box.maxZ);bz++){
                            var b=new BlockPos(bx,by,bz);for(var shape:run.helper.getLevel().getBlockState(b).getCollisionShape(run.helper.getLevel(),b).toAabbs()){
                                var world=shape.move(bx,by,bz);if(world.intersects(box))hits.add(Map.of("block",new int[]{bx,by,bz},"shape",new double[]{world.minX,world.minY,world.minZ,world.maxX,world.maxY,world.maxZ}));
                            }
                        }
                        bodyCalls.add(Map.of("floor",y,"gx",gx,"gz",gz,"actual_body_clear",clear,"central_aabb",new double[]{box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ},"actual_central_collisions",hits));
                        // Diagnostic counterfactual only: never used by nav or physical control.
                        return true;
                    }
                };
                var relaxed=new LeaperPredatorPlanner(wrapped,origin.x,7,origin.z);
                var row=new LinkedHashMap<String,Object>();row.put("progress",progress);row.put("x",x);row.put("z",z);row.put("actual_floor",floor);
                row.put("actual_measure",supportRecord(measure.invoke(planner,x,floor,z)));
                row.put("diagnostic_measure_ignoring_body_collision_only",supportRecord(measure.invoke(relaxed,x,floor,z)));
                row.put("body_clear_calls",bodyCalls);stanceProbe.add(row);
            }
        }
        void fail(String why,boolean physical){failures.add(why);physicalFailure|=physical;done=true;success=false;}
        void input(String event){inputs.add(Map.of("event",event,"tick",tick,"position",xyz(rig.corePivot())));}
        void step(){if(done)return;tick++;try{
            if(tick==SETTLE+1){require(nav.go(goal.x,goal.y,goal.z),"Native goal rejected");input("go");previous=rig.corePivot();peakProgress=progress(previous);stallReference=peakProgress;stallTick=tick;}
            if(spec.kind==4 && removedTick<0 && tick>=100 && nav.planning==null && progress(rig.corePivot())>=2){
                var p=rig.corePivot();int x=(int)Math.floor(p.x),z=(int)Math.floor(p.z),removed=0;
                for(int dx=-12;dx<=12;dx++)for(int dz=-12;dz<=12;dz++){
                    var b=new BlockPos(x+dx,6,z+dz);require(run.helper.getLevel().getBlockState(b).is(Blocks.SMOOTH_STONE),"Gap fixture floor changed");
                    run.helper.getLevel().setBlockAndUpdate(b,Blocks.AIR.defaultBlockState());removed++;
                }
                require(removed==625,"Gap patch incomplete");removedTick=tick;input("native-floor-removed");
            }
            int countBefore=rig.terrainAssistImpulses;double climbBefore=metric(rig,"climbAssistImpulses");var comBefore=rig.assemblyVelocity();var vBefore=rig.velocity();var pBefore=rig.corePivot();int supportsBefore=nav.leap.supports();
            boolean genuineFall=removedTick>=0 && pBefore.y<5 && vBefore.y<-3 && supportsBefore==0;
            if(genuineFall && assistBeforeFall<0){assistBeforeFall=countBefore;climbBeforeFall=climbBefore;}
            long began=System.nanoTime();nav.step();maxStepMillis=Math.max(maxStepMillis,(System.nanoTime()-began)/1e6);
            var p=rig.corePivot();var v=rig.velocity();var comV=rig.assemblyVelocity();int supports=nav.leap.supports();
            anchor=Math.max(anchor,rig.anchorError());limit=Math.max(limit,rig.limitViolation());minUp=Math.min(minUp,rig.upright());
            require(nav.leap.launches==0 && rig.leapImpulses.isEmpty(),"Walking benchmark used takeoff impulse");
            double climbAfter=metric(rig,"climbAssistImpulses");require(climbAfter>=climbBefore && climbAfter-climbBefore<=1,"More than one climb impulse per control tick");
            require(metric(rig,"climbAssistMaximumHorizontal")<=3.0001 && metric(rig,"climbAssistMaximumVertical")<=1.251,"Climb application cap exceeded");
            var immediateDelta=new Vector3d(comV).sub(comBefore);
            double energyBefore=comBefore.x*comBefore.x+comBefore.z*comBefore.z,energyAfter=comV.x*comV.x+comV.z*comV.z;
            if(climbAfter>climbBefore){
                require(rig.terrainAssistImpulses==countBefore,"Cannot independently audit simultaneous old/climb assists");
                double deltaH=Math.hypot(immediateDelta.x,immediateDelta.z);
                require(immediateDelta.isFinite() && deltaH<=3.0001 && immediateDelta.y>=-.7001 && immediateDelta.y<=1.2501,"Native climb delta cap");
                if(deltaH>.6001){require(energyAfter<energyBefore-1e-5,"Above.6 climb impulse failed to reduce measured horizontal energy");verifiedImpactBrakes++;}
            }else if(rig.terrainAssistImpulses>countBefore)
                require(immediateDelta.isFinite() && Math.hypot(immediateDelta.x,immediateDelta.z)<=.3011 && immediateDelta.y>=-.0001 && immediateDelta.y<=.4511,"Native old assist delta cap");
            if(genuineFall){require(rig.terrainAssistImpulses==countBefore && rig.terrainAssistImpulses==assistBeforeFall && climbAfter==climbBefore && climbAfter==climbBeforeFall,"Assist during genuine native freefall");freefallSamples++;}
            if(stopTick>=0){require(nav.goal==null && nav.pendingGoal==null && rig.terrainAssistImpulses==stopCount && climbAfter==stopClimb,"Stop did not suppress assistance/goal");stopDrift=Math.max(stopDrift,horizontal(p,stopPosition));}
            error=horizontal(p,goal);double along=progress(p),side=Math.abs((p.x-origin.x)*Math.cos(spec.angle)+(p.z-origin.z)*Math.sin(spec.angle));
            if(tick>SETTLE && stopTick<0 && spec.kind!=4){
                backward+=Math.max(0,progress(previous)-along);peakProgress=Math.max(peakProgress,along);maxSetback=Math.max(maxSetback,peakProgress-along);maxSide=Math.max(maxSide,side);
                double reverse=-(comV.x*Math.sin(spec.angle)-comV.z*Math.cos(spec.angle));maxReverse=Math.max(maxReverse,reverse);
                if(reverse>4 && !reverseBurst)bursts++;reverseBurst=reverse>4;
                if(along>=stallReference+.5){stallReference=along;stallTick=tick;}longestStall=Math.max(longestStall,(tick-stallTick)*.05);
                int lastFace=spec.kind==1?(run.wideStairs?16:14):12;boolean crossed=spec.kind>=3;
                if(spec.kind<3){crossed=true;for(int leg=0;leg<4;leg++)crossed&=origin.z-rig.toe(leg).z>=lastFace+.6;}
                if(crossed && crossTick<0)crossTick=tick;
                boolean at=nav.arrived && error<=2.5 && Math.abs(p.y-goal.y-run.policy.value[4])<1.25 && Math.hypot(v.x,v.z)<.75 && Math.abs(v.y)<.75;
                arrivalRun=at?arrivalRun+1:0;
                if(arrivalRun>=40 && crossTick>=0){arrivalTick=tick-39;nav.stop();stopTick=tick;stopPosition=new Vector3d(p);stopCount=rig.terrainAssistImpulses;stopClimb=climbAfter;input("stop-after-settled-arrival");}
            }
            var row=new LinkedHashMap<String,Object>();row.put("tick",tick);row.put("core",xyz(p));row.put("velocity",xyz(v));row.put("com_velocity",xyz(comV));row.put("progress",along);row.put("error",error);row.put("side",side);
            row.put("state",nav.leap.state.toString());row.put("planning",nav.planning!=null);row.put("arrived",nav.arrived);row.put("hold",nav.holdPosition);row.put("supports",supports);row.put("genuine_freefall",genuineFall);
            row.put("terrain_assist_impulses",rig.terrainAssistImpulses);row.put("terrain_assist_delta_total",rig.terrainAssistDeltaTotal);row.put("anchor",rig.anchorError());row.put("limit",rig.limitViolation());row.put("up",rig.upright());
            row.put("climb_assist_impulses",climbAfter);row.put("climb_assist_delta_total",metric(rig,"climbAssistDeltaTotal"));
            row.put("nav_reason",nav.reason);row.put("route_blocked",nav.routeBlocked);
            row.put("ground_search_ticks",nav.planning==null?-1:nav.planning.ticks);
            row.put("assist_com_delta_after_api",xyz(immediateDelta));
            row.put("assist_horizontal_speed_squared_before",energyBefore);row.put("assist_horizontal_speed_squared_after",energyAfter);
            row.put("bounded_angles",rig.boundedMotorTargets());row.put("measured_angles",rig.angles());
            var toes=new ArrayList<Object>();for(int leg=0;leg<4;leg++)toes.add(xyz(rig.toe(leg)));row.put("toes",toes);
            history.add(MinecraftMachinesLeaperPursuitRegressionTests.clean(MinecraftMachinesLeaperPursuitRegressionTests.JSON.toJsonTree(row),"tick"+tick,nonfinite));previous=new Vector3d(p);lastAssist=rig.terrainAssistImpulses;
            if(!rig.valid() || !p.isFinite() || !v.isFinite() || !nonfinite.isEmpty() || anchor>.12 || limit>.15 || rig.upright()<.45){fail("Native finite/joint/fall guard",true);return;}
            if(run.probeOnly && tick>SETTLE+1 && nav.planning==null){done=true;return;}
            if(spec.kind==4 && freefallSamples>=40)finish();
            else if(stopTick>=0 && tick-stopTick>=STOP)finish();
            else if(stopTick<0 && tick>=SETTLE+budget()){failures.add("Arrival/freefall budget exceeded");finish();}
        }catch(Exception e){fail("native: "+e,false);}}
        void finish(){done=true;
            if(spec.kind==4){if(removedTick<0 || freefallSamples<40)failures.add("Native gap/freefall unexercised");}
            else{
                if(crossTick<0 || arrivalTick<0 || stopTick<0 || tick-stopTick<STOP)failures.add("Whole-rig crossing/arrival/stop incomplete");
                if(backward>6 || maxSetback>3 || bursts>3)failures.add("Excessive backward motion");
                if(maxSide>6)failures.add("Route corridor exceeded; obstacle detour not accepted");
                if(stopDrift>1.5)failures.add("Stop drift above1.5");
            }success=failures.isEmpty()&&!physicalFailure&&nonfinite.isEmpty();
        }
        Object result(){var r=new LinkedHashMap<String,Object>();r.put("case",spec);r.put("attempted",rig!=null);r.put("complete",done);r.put("success",success);r.put("failures",failures);r.put("physical_failure",physicalFailure);r.put("ticks",tick);
            r.put("arrival_tick",arrivalTick);r.put("arrival_seconds",arrivalTick<0?null:(arrivalTick-SETTLE)*.05);r.put("cross_tick",crossTick);r.put("backward_travel",backward);r.put("maximum_setback",maxSetback);r.put("reverse_bursts_over4bps",bursts);r.put("worst_reverse_speed",maxReverse);r.put("longest_stall_seconds",longestStall);r.put("maximum_side_deviation",maxSide);r.put("final_distance",error);
            r.put("stop_tick",stopTick);r.put("stop_drift",stopDrift);r.put("stop_samples",stopTick<0?0:tick-stopTick);r.put("gap_removed_tick",removedTick);r.put("genuine_freefall_samples",freefallSamples);r.put("assist_count_at_freefall",assistBeforeFall);
            r.put("anchor_error",anchor);r.put("limit_violation",limit);r.put("minimum_up",minUp);r.put("maximum_step_ms",maxStepMillis);r.put("metadata",metadata);r.put("nonfinite_fields",nonfinite);r.put("history",history);r.put("inputs",inputs);
            r.put("desired_quality_success",success && backward<=3 && maxSetback<=1.5 && bursts<=1);
            r.put("verified_energy_reducing_impact_brakes",verifiedImpactBrakes);
            r.put("nav_reason",nav==null?null:nav.reason);r.put("stance_probe",stanceProbe);r.put("diagnostic_only",run.probeOnly);
            r.put("climb_assist_impulses",rig==null?0:metric(rig,"climbAssistImpulses"));r.put("climb_assist_delta_total",rig==null?0:metric(rig,"climbAssistDeltaTotal"));r.put("climb_assist_max_horizontal",rig==null?0:metric(rig,"climbAssistMaximumHorizontal"));r.put("climb_assist_max_vertical",rig==null?0:metric(rig,"climbAssistMaximumVertical"));
            r.put("terrain_assist_impulses",rig==null?0:rig.terrainAssistImpulses);r.put("terrain_assist_delta_total",rig==null?0:rig.terrainAssistDeltaTotal);r.put("terrain_assist_max_horizontal",rig==null?0:rig.terrainAssistMaximumHorizontal);r.put("terrain_assist_max_vertical",rig==null?0:rig.terrainAssistMaximumVertical);r.put("leap_impulses",rig==null?List.of():rig.leapImpulses);return r;}
        void close(){if(closed)return;closed=true;if(rig!=null)rig.close();}
    }
    static final class Run{
        final GameTestHelper helper;final List<Trial> trials=new ArrayList<>();MinecraftMachinesLeaperPredatorPolicy policy;int current,oldSubsteps;boolean changed;
        final boolean probeOnly,wideStairs;final String mode;
        Run(GameTestHelper h){helper=h;mode=MinecraftMachinesLeaperLearning.mode();probeOnly=mode.equals(PROBE_MODE);wideStairs=mode.equals(WIDE_MODE);}
        void begin()throws Exception{
            require(!Boolean.getBoolean("minecraft_machines.leaperLeap") && Boolean.getBoolean("minecraft_machines.leaperAgile") && MinecraftMachinesLeaperLearning.profile().equals("horrific"),"Require leapfalse/agiletrue/Hprofile");
            require(Files.isRegularFile(Path.of("leaper_learning","horrific-predator-candidate.json")),"Explicit baseline genome required");
            policy=MinecraftMachinesLeaperPredatorExperiments.candidate();var system=SubLevelPhysicsSystem.get(helper.getLevel());oldSubsteps=system.getConfig().substepsPerTick;system.getConfig().substepsPerTick=4;system.onConfigUpdated();changed=true;
            for(int i=0;i<CASES.size();i++)if(!(probeOnly||wideStairs) || CASES.get(i).kind==1)trials.add(new Trial(this,i));trials.getFirst().initialize();persist();loop();
        }
        void loop(){helper.runAfterDelay(1,()->{try{var t=trials.get(current);t.step();if(t.done){t.close();persist();if(++current==trials.size()){close();helper.succeed();return;}trials.get(current).initialize();}loop();}catch(Exception e){for(var t:trials)if(!t.done)t.fail("harness: "+e,false);close();try{persist();}catch(Exception ignored){}helper.fail(e.toString());}});}
        void persist()throws Exception{var r=new LinkedHashMap<String,Object>();r.put("mode",mode);r.put("qualified",false);r.put("diagnostic_only",probeOnly);r.put("criteria",criteria());r.put("expected_cases",trials.size());r.put("complete",trials.stream().allMatch(t->t.done));r.put("control_hz",20);r.put("leap_enabled",false);r.put("genome",policy.genome);r.put("physics",SubLevelPhysicsSystem.get(helper.getLevel()).getConfig());r.put("trials",trials.stream().map(Trial::result).toList());MinecraftMachinesLeaperLearning.write(mode,r);}
        void close(){for(var t:trials)t.close();if(changed){var system=SubLevelPhysicsSystem.get(helper.getLevel());system.getConfig().substepsPerTick=oldSubsteps;system.onConfigUpdated();changed=false;}}
    }
}
