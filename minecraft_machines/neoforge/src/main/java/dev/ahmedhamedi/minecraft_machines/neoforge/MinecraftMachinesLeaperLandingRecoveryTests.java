package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.JsonElement;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Native predator handoff regression: usable contact resumes locomotion; freefall never does. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperLandingRecoveryTests {
    static final String MODE="predator-landing-recovery";
    static final int LIMIT=1400;
    static final String[] NAMES={"uneven-contact-pending", "flat-contact-pending", "removed-pad-freefall", "rough-stairs-assist-stop", "partial-pad-loss-pending"};
    @GameTest(template="empty",timeoutTicks=8000,batch="leaperLandingRecovery")
    public static void recovery(GameTestHelper helper) throws Exception {
        if(!MinecraftMachinesLeaperLearning.mode().equals(MODE)){helper.succeed();return;}
        var run=new Run(helper);
        try{run.begin();}catch(Exception e){run.close();throw e;}
    }
    static void require(boolean ok,String why){if(!ok)throw new IllegalStateException(why);}
    static double[] xyz(Vector3d v){return new double[]{v.x,v.y,v.z};}
    static Object optional(Vector3d v){return v==null?null:xyz(v);}
    static double distance(Vector3d a,Vector3d b){return Math.hypot(a.x-b.x,a.z-b.z);}

    static final class Trial {
        final Run run;final int id;
        final MinecraftMachinesLeaperPursuitRegressionTests.Trial fixture;
        final List<JsonElement> history=new ArrayList<>(),inputs=new ArrayList<>();
        final List<String> failures=new ArrayList<>(),nonfinite=new ArrayList<>();
        final List<BlockPos> pad=new ArrayList<>();
        final Map<BlockPos,BlockState> partialPad=new LinkedHashMap<>();
        int partialRemovalTick=-1,partialRestoreTick=-1;
        final List<Object> assists=new ArrayList<>();
        int lastAssistTick=-1,roughAssists,unassistedFlightSamples,unassistedHoldSamples,unassistedPlanningSamples;
        int stopTick=-1,stopAssistCount=-1,stoppedSamples;
        double actualAssistMaxHorizontal,actualAssistMaxVertical,maximumBodyDeltaDisagreement;
        MinecraftMachinesLeaper rig;MinecraftMachinesLeaperPredatorNavigator nav;
        List<ServerSubLevel> bodies=List.of();
        Vector3d target,queued,releasePosition,lastPosition;
        int tick,launchTick=-1,firstLandTick=-1,queueTick=-1,releaseTick=-1,entryTick=-1;
        int quietRun,lowSupportQuietRun,maxLowSupportQuiet,strictRun,fallLandTicks,freeFlightSamples,stableTail;
        int releaseSupports=-1,releaseLandTicks,releaseQuietTicks,releaseStrictTicks,releaseStableLandingTick=-1;
        int firstUsableContactTick=-1, usableContactRun, releaseContactDelay=-1;
        boolean releaseWasUsable;
        boolean attempted,done,closed,success,padRemoved,pendingPreserved=true,queuedActivated;
        String recoveryEvent="";
        double anchor,limit,minUp=1,postReleaseProgress,finalDistance,minimumY=Double.POSITIVE_INFINITY;
        Trial(Run run,int id){this.run=run;this.id=id;fixture=new MinecraftMachinesLeaperPursuitRegressionTests.Trial(run.fixtures,3);}
        boolean landingCase(){return id==0 || id==1 || id==4;}
        void initialize(){
            attempted=true;
            try{
                fixture.initialize();require(!fixture.done,"Base arena: "+fixture.failures);
                rig=fixture.rig;nav=fixture.nav;nav.pace="horrific";
                var bodyField=MinecraftMachinesLeaper.class.getDeclaredField("bodies");bodyField.setAccessible(true);
                @SuppressWarnings("unchecked") var actualBodies=(List<ServerSubLevel>)bodyField.get(rig);
                bodies=List.copyOf(actualBodies);require(bodies.size()==13,"Expected13 native bodies");
                var level=run.helper.getLevel();int ox=(int)Math.floor(fixture.origin.x),oz=(int)Math.floor(fixture.origin.z);
                if(id==0){
                    // Northward arrival straddles a one-block lateral step: both right feet high,
                    // both left feet low. Landing pad variation remains within planner allowance.
                    for(int x=ox;x<=ox+128;x++)for(int z=oz-24;z>=oz-128;z--)
                        level.setBlockAndUpdate(new BlockPos(x,7,z),Blocks.STONE.defaultBlockState());
                }
                if(id==2){
                    // Clear a genuine void before planning. Keep a small real destination pad
                    // until takeoff; removing only that pad then tests a failed physical landing.
                    for(int cx=(ox-42)>>4;cx<=(ox+42)>>4;cx++)for(int cz=(oz-402)>>4;cz<=(oz-10)>>4;cz++)level.setChunkForced(cx,cz,true);
                    for(int x=ox-40;x<=ox+40;x++)for(int z=oz-12;z>=oz-400;z--)for(int y=level.getMinBuildHeight();y<=8;y++){
                        var pos=new BlockPos(x,y,z);if(!level.getBlockState(pos).isAir())level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
                    }
                    for(int x=ox-12;x<=ox+12;x++)for(int z=oz-60;z<=oz-36;z++){
                        var p=new BlockPos(x,6,z);pad.add(p);level.setBlockAndUpdate(p,Blocks.STONE.defaultBlockState());
                    }
                }
                if(id==3){
                    // A real two-block riser six blocks ahead, with a broad supported apron.
                    // The close twelve-block goal exercises walking rather than a forced leap.
                    for(int x=ox-24;x<=ox+24;x++)for(int z=oz-6;z>=oz-40;z--)
                        for(int y=7;y<=8;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState());
                }
                target=new Vector3d(fixture.origin.x,id==3?9:id==0?8:7,fixture.origin.z-(id==3?12:48));
                queued=new Vector3d(fixture.origin.x,id==0?8:7,fixture.origin.z-66);
                if(id==3)queued=new Vector3d(target);
                require(Math.abs(nav.map.height(target.x,target.z,target.y+.55,72)-target.y)<1e-6,"Initial target unsupported");
                if(id!=2)require(Math.abs(nav.map.height(queued.x,queued.z,queued.y+.55,72)-queued.y)<1e-6,"Queued target unsupported");
            }catch(Exception e){fail("fixture: "+e);}
        }
        void fail(String why){failures.add(why);done=true;success=false;}
        void event(String name){
            var r=new LinkedHashMap<String,Object>();r.put("event",name);r.put("tick",tick);r.put("state",nav.leap.state.toString());
            r.put("position",xyz(rig.corePivot()));r.put("goal",optional(nav.goal));r.put("pending_goal",optional(nav.pendingGoal));
            inputs.add(MinecraftMachinesLeaperPursuitRegressionTests.JSON.toJsonTree(r));
        }
        void step(){
            if(done)return;tick++;
            try{
                if(tick==81){require(nav.go(target.x,target.y,target.z),"Initial native goal rejected");event("initial-go");}
                if(id==2 && !padRemoved && nav.leap.state==MinecraftMachinesLeaperLeap.State.FLIGHT){
                    require(nav.leap.launches==1,"Pad removal must follow a real single launch");
                    for(var p:pad){require(run.helper.getLevel().getBlockState(p).is(Blocks.STONE),"Pad changed before removal");run.helper.getLevel().setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());}
                    for(var p:pad)require(run.helper.getLevel().getBlockState(p).isAir(),"Pad collider removal failed");
                    padRemoved=true;event("native-pad-removed");
                }
                if(id==4 && partialRemovalTick<0 && nav.leap.state==MinecraftMachinesLeaperLeap.State.LAND){
                    require(nav.leap.supports()>=3,"Partial-pad fixture must begin with actual broad support");
                    // A physical support transition: two diagonal foot pads disappear for
                    // at least one native interval. No pose, velocity, or controller injection.
                    var level=run.helper.getLevel();
                    for(int leg:new int[]{0,3}){
                        var toe=rig.toe(leg);int x=(int)Math.floor(toe.x),z=(int)Math.floor(toe.z);
                        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int y=4;y<=6;y++){
                            var b=new BlockPos(x+dx,y,z+dz);var state=level.getBlockState(b);
                            if(!state.isAir()){partialPad.putIfAbsent(b,state);level.setBlockAndUpdate(b,Blocks.AIR.defaultBlockState());}
                        }
                    }
                    require(!partialPad.isEmpty() && nav.leap.supports()>=1 && nav.leap.supports()<=2,"Native partial-pad removal did not leave1/2supports");
                    partialRemovalTick=tick;event("native-two-foot-pad-loss");
                }
                if(id==4 && releaseTick>=0 && partialRestoreTick<0){
                    partialPad.forEach(run.helper.getLevel()::setBlockAndUpdate);partialRestoreTick=tick;event("native-pads-restored-after-handoff");
                }
                if(landingCase() && queueTick<0 && nav.leap.state==MinecraftMachinesLeaperLeap.State.LAND){
                    var commitment=nav.leap.active;
                    require(nav.go(queued.x,queued.y,queued.z),"LAND retarget rejected");
                    require(nav.pendingGoal!=null && nav.pendingGoal.distanceSquared(queued)<1e-8,"LAND destination was not queued");
                    require(nav.leap.active==commitment && nav.leap.state==MinecraftMachinesLeaperLeap.State.LAND,"Retarget interrupted landing");
                    queueTick=tick;event("queued-during-native-LAND");
                }
                if(id==3 && stopTick<0 && tick>=180 && roughAssists>0
                        && distance(fixture.origin,target)-distance(rig.corePivot(),target)>=4
                        && nav.leap.state==MinecraftMachinesLeaperLeap.State.WALK && nav.planning==null){
                    nav.stop();stopTick=tick;stopAssistCount=rig.terrainAssistImpulses;event("explicit-stop-after-rough-assist");
                }
                var before=nav.leap.state;int beforeTicks=nav.leap.stateTicks;
                var p=rig.corePivot();var v=rig.velocity();var angular=rig.angularVelocity();
                int supports=nav.leap.supports();double up=rig.upright();
                boolean quiet=v.length()<=.75 && angular.length()<=1 && up>=.65;
                // Independent pre-control observation. A hard impact can be unusable at
                // touchdown, so latency starts at usable contact, not at the first impact.
                boolean usable=before==MinecraftMachinesLeaperLeap.State.LAND
                        && beforeTicks+1>=2 && supports>=1 && up>=.60 && v.y>=-5 && v.y<=4;
                usableContactRun=usable?usableContactRun+1:0;
                if(usable && releaseTick<0 && firstUsableContactTick<0)firstUsableContactTick=tick;
                if(before==MinecraftMachinesLeaperLeap.State.LAND){
                    quietRun=quiet?quietRun+1:0;
                    lowSupportQuietRun=quiet && supports>=1 && supports<=2?lowSupportQuietRun+1:0;
                    maxLowSupportQuiet=Math.max(maxLowSupportQuiet,lowSupportQuietRun);
                    strictRun=supports>=3 && up>=.85 && Math.abs(v.y)<=.5 && Math.hypot(v.x,v.z)<=1?strictRun+1:0;
                    if(id==2)fallLandTicks++;
                    if(queueTick>=0 && releaseTick<0)pendingPreserved &= nav.pendingGoal!=null && nav.pendingGoal.distanceSquared(queued)<1e-8;
                }else{quietRun=lowSupportQuietRun=strictRun=0;}
                if(before==MinecraftMachinesLeaperLeap.State.FLIGHT)freeFlightSamples++;
                int eventsBefore=nav.leap.events.size();
                int assistBefore=rig.terrainAssistImpulses,leapsBefore=rig.leapImpulses.size();
                double totalBefore=rig.terrainAssistDeltaTotal;
                boolean heldBefore=nav.holdPosition || nav.arrived || nav.goal==null;
                boolean plannedBefore=nav.planning!=null;
                var comBefore=rig.assemblyVelocity();
                var bodyBefore=new ArrayList<Vector3d>();
                for(var body:bodies)bodyBefore.add(RigidBodyHandle.of(body).getLinearVelocity(new Vector3d()));
                nav.step();
                auditAssist(before,supports,heldBefore,plannedBefore,assistBefore,leapsBefore,totalBefore,comBefore,bodyBefore);
                if(launchTick<0 && nav.leap.launches>0)launchTick=tick;
                if(firstLandTick<0 && nav.leap.state==MinecraftMachinesLeaperLeap.State.LAND)firstLandTick=tick;
                for(int e=eventsBefore;e<nav.leap.events.size();e++){
                    String type=(String)nav.leap.events.get(e).get("event");
                    if(type.equals("contact-resume") || type.equals("terrain-recovery") || type.equals("recovered")){
                        require(id!=2,"Freefall falsely released to terrain control");
                        require(before==MinecraftMachinesLeaperLeap.State.LAND,"Recovery did not originate in LAND");
                        if(type.equals("contact-resume"))require(usable,"Contact handoff without usable native contact");
                        if(type.equals("terrain-recovery"))require(beforeTicks+1>=10 && quietRun>=8,"Quiet fallback violated10-LAND/8-quiet minimum");
                        if(releaseTick<0){
                            recoveryEvent=type;releaseTick=tick;releasePosition=new Vector3d(p);
                            releaseWasUsable=usable;
                            releaseContactDelay=firstUsableContactTick<0?-1:tick-firstUsableContactTick;
                            releaseSupports=supports;releaseLandTicks=beforeTicks+1;releaseQuietTicks=quietRun;releaseStrictTicks=strictRun;
                            releaseStableLandingTick=nav.leap.stableLandingTick;
                            queuedActivated=nav.goal!=null && nav.goal.distanceSquared(queued)<1e-8 && nav.pendingGoal==null;
                        }
                    }
                }
                if(releaseTick<0 && usableContactRun>6 && nav.leap.state==MinecraftMachinesLeaperLeap.State.LAND)
                    fail("Six consecutive usable contact ticks without locomotion handoff");
                if(id==2 && before==MinecraftMachinesLeaperLeap.State.FLIGHT)
                    require(nav.leap.state==MinecraftMachinesLeaperLeap.State.FLIGHT || nav.leap.state==MinecraftMachinesLeaperLeap.State.LAND,"Flight bypassed landing");
                sample(before,beforeTicks,p,v,angular,supports,quiet);
                if(done)return;
                if(id==2 && fallLandTicks>=90)finish();
                else if(id==3 && stoppedSamples>=80)finish();
                else if(landingCase() && entryTick>=0 && tick-entryTick>=80 && stableTail>=40)finish();
                else if(tick>=LIMIT){failures.add("Timeout: required landing recovery and queued progress incomplete");finish();}
            }catch(Exception e){fail("native landing: "+e);}
        }
        void auditAssist(MinecraftMachinesLeaperLeap.State before,int supports,boolean held,boolean planned,
                         int countBefore,int leapsBefore,double totalBefore,Vector3d comBefore,List<Vector3d> bodyBefore){
            int count=rig.terrainAssistImpulses-countBefore;
            require(count>=0 && count<=1,"Unexpected terrain assist counter delta");
            require(Double.isFinite(rig.terrainAssistDeltaTotal) && rig.terrainAssistDeltaTotal>=totalBefore
                    && Double.isFinite(rig.terrainAssistMaximumHorizontal) && Double.isFinite(rig.terrainAssistMaximumVertical)
                    && rig.terrainAssistMaximumHorizontal<=.301 && rig.terrainAssistMaximumVertical<=.451,"Applied assist cap/counter violation");
            if(before==MinecraftMachinesLeaperLeap.State.FLIGHT || before==MinecraftMachinesLeaperLeap.State.LAND){
                require(count==0,"Assist during FLIGHT/LAND");unassistedFlightSamples++;
            }
            boolean stillHeld=held && (nav.holdPosition || nav.arrived || nav.goal==null);
            if(stillHeld){require(count==0,"Assist during hold/arrival/no goal");unassistedHoldSamples++;}
            if(planned){require(count==0,"Assist during route planning");unassistedPlanningSamples++;}
            if(stopTick>=0){require(nav.goal==null && nav.pendingGoal==null && rig.terrainAssistImpulses==stopAssistCount,"Stop did not suppress assistance/goal");stoppedSamples++;}
            if(count==0){require(rig.terrainAssistDeltaTotal==totalBefore,"Assist total changed without application counter");return;}
            require(before==MinecraftMachinesLeaperLeap.State.WALK && nav.leap.state==MinecraftMachinesLeaperLeap.State.WALK
                    && supports>=1 && !stillHeld && !planned,"Assist without walking support");
            require(lastAssistTick<0 || tick-lastAssistTick>=2,"Assist interval below two ticks");lastAssistTick=tick;
            require(rig.leapImpulses.size()==leapsBefore,"Assist polluted takeoff counter");
            var delta=rig.assemblyVelocity().sub(comBefore);
            double h=Math.hypot(delta.x,delta.z);
            require(delta.isFinite() && h<=.3011 && delta.y>=-.0001 && delta.y<=.4511,"Measured native COM assist delta out of bounds");
            require(Math.abs(delta.length()-(rig.terrainAssistDeltaTotal-totalBefore))<.0001,"Applied native delta disagrees with counter");
            require(delta.length()>1e-6,"Counter increment without physical velocity delta");
            actualAssistMaxHorizontal=Math.max(actualAssistMaxHorizontal,h);actualAssistMaxVertical=Math.max(actualAssistMaxVertical,delta.y);
            var bodyDeltas=new ArrayList<Object>();
            for(int i=0;i<bodies.size();i++){
                var d=RigidBodyHandle.of(bodies.get(i)).getLinearVelocity(new Vector3d()).sub(bodyBefore.get(i));
                maximumBodyDeltaDisagreement=Math.max(maximumBodyDeltaDisagreement,d.distance(delta));
                require(d.isFinite() && d.distance(delta)<.0001,"Mass-proportional assist not uniform across native bodies");bodyDeltas.add(xyz(d));
            }
            boolean rough=nav.control.clearGroundBlend<.8;if(rough)roughAssists++;
            var row=new LinkedHashMap<String,Object>();row.put("tick",tick);row.put("rough",rough);row.put("supports",supports);
            row.put("remaining_distance",nav.distance);row.put("com_delta_after_api",xyz(delta));row.put("body_deltas_after_api",bodyDeltas);
            row.put("total_delta_increment",rig.terrainAssistDeltaTotal-totalBefore);assists.add(row);
        }
        void sample(MinecraftMachinesLeaperLeap.State before,int beforeTicks,Vector3d p,Vector3d v,Vector3d angular,int supports,boolean quiet){
            lastPosition=new Vector3d(rig.corePivot());minimumY=Math.min(minimumY,lastPosition.y);
            anchor=Math.max(anchor,rig.anchorError());limit=Math.max(limit,rig.limitViolation());minUp=Math.min(minUp,rig.upright());
            finalDistance=distance(lastPosition,queued);
            if(releaseTick>=0){
                postReleaseProgress=Math.max(postReleaseProgress,distance(releasePosition,queued)-finalDistance);
                boolean atFloor=Math.abs(lastPosition.y-queued.y-run.policy.value[4])<1.25;
                if(entryTick<0 && finalDistance<=2.5 && atFloor)entryTick=tick;
                boolean stable=finalDistance<=2.5 && atFloor && v.length()<1 && rig.upright()>.65;
                stableTail=stable?stableTail+1:0;
            }
            var row=new LinkedHashMap<String,Object>();row.put("tick",tick);row.put("before_state",before.toString());row.put("before_state_ticks",beforeTicks);
            row.put("state",nav.leap.state.toString());row.put("state_ticks",nav.leap.stateTicks);row.put("core",xyz(p));row.put("velocity",xyz(v));row.put("angular_velocity",xyz(angular));
            row.put("supports_proximity_estimate",supports);row.put("quiet",quiet);row.put("independent_quiet_ticks",quietRun);row.put("independent_low_support_quiet_ticks",lowSupportQuietRun);
            row.put("independent_strict_ticks",strictRun);row.put("stable_landing_tick",nav.leap.stableLandingTick);row.put("launches",nav.leap.launches);
            row.put("usable_contact_run",usableContactRun);row.put("first_usable_contact_tick",firstUsableContactTick);
            row.put("goal",optional(nav.goal));row.put("pending_goal",optional(nav.pendingGoal));row.put("planning",nav.planning!=null);row.put("arrived",nav.arrived);
            row.put("hold",nav.holdPosition);row.put("terrain_assist_impulses",rig.terrainAssistImpulses);row.put("terrain_assist_delta_total",rig.terrainAssistDeltaTotal);
            row.put("anchor",rig.anchorError());row.put("limit",rig.limitViolation());row.put("up",rig.upright());row.put("angles",rig.angles());row.put("bounded_angles",rig.boundedMotorTargets());
            var toes=new ArrayList<Object>();for(int i=0;i<4;i++)toes.add(xyz(rig.toe(i)));row.put("toes",toes);
            history.add(MinecraftMachinesLeaperPursuitRegressionTests.clean(MinecraftMachinesLeaperPursuitRegressionTests.JSON.toJsonTree(row),"tick"+tick,nonfinite));
            if(!rig.valid() || !p.isFinite() || !v.isFinite() || !angular.isFinite() || !nonfinite.isEmpty() || anchor>.12 || limit>.15 || rig.upright()<.45)fail("Native finite/physical guard");
        }
        void finish(){
            done=true;
            if(id!=3 && (launchTick<0 || freeFlightSamples<5))failures.add("Real native launch/flight unexercised");
            if(id==3 && (roughAssists<=0 || assists.isEmpty() || actualAssistMaxHorizontal<=0 || stoppedSamples<80
                    || distance(fixture.origin,target)-distance(lastPosition,target)<4 || nav.leap.launches!=0))
                failures.add("Rough native walking assist, four-block progress, no leap and80stopped samples not exercised");
            if(id==4){
                if(releaseSupports<1 || releaseSupports>2 || partialRemovalTick<0 || releaseTick<=partialRemovalTick || partialRestoreTick<=releaseTick)
                    failures.add("Physical1/2-support handoff after native interval and post-handoff pad restoration not exercised");
            }
            if(landingCase() && (!recoveryEvent.equals("contact-resume") || !releaseWasUsable
                    || releaseLandTicks<2 || releaseContactDelay<0 || releaseContactDelay>6))
                failures.add("Missing prompt contact handoff within6ticks of usable contact");
            // stableLandingTick is now a legacy-named handoff timestamp, not strict
            // fifteen-sample support proof. Do not use it as the acceptance criterion.
            if(landingCase() && (queueTick<0 || !pendingPreserved || !queuedActivated || postReleaseProgress<6 || entryTick<0 || tick-entryTick<80 || stableTail<40))
                failures.add("Queued goal not retained, activated, approached6blocks and settled");
            if(id==2){
                if(!padRemoved || fallLandTicks<90 || minimumY> -30 || nav.leap.launches!=1 || nav.leap.stableLandingTick!=0)
                    failures.add("Long native freefall after pad removal unexercised");
                if(nav.leap.events.stream().noneMatch(e->e.get("event").equals("flight-timeout")))failures.add("Missing physical missed-landing transition");
                if(nav.leap.events.stream().anyMatch(e->Set.of("contact-resume","terrain-recovery","recovered","touchdown").contains(e.get("event"))))failures.add("Freefall had an unexpected landing/recovery");
            }
            success=failures.isEmpty() && nonfinite.isEmpty();
        }
        Object result(){
            var r=new LinkedHashMap<String,Object>();r.put("case",NAMES[id]);r.put("attempted",attempted);r.put("complete",done);r.put("success",success);r.put("failures",failures);r.put("ticks",tick);
            r.put("initial_target",optional(target));r.put("queued_target",optional(queued));r.put("launch_tick",launchTick);r.put("first_land_tick",firstLandTick);r.put("queue_tick",queueTick);
            r.put("recovery_event",recoveryEvent);r.put("release_tick",releaseTick);r.put("release_supports",releaseSupports);r.put("release_land_ticks",releaseLandTicks);r.put("release_quiet_ticks",releaseQuietTicks);
            r.put("release_strict_ticks",releaseStrictTicks);r.put("release_stable_landing_tick",releaseStableLandingTick);r.put("max_low_support_quiet_ticks",maxLowSupportQuiet);
            r.put("first_usable_contact_tick",firstUsableContactTick);r.put("release_contact_delay_ticks",releaseContactDelay);r.put("release_was_usable_contact",releaseWasUsable);
            r.put("pending_preserved",pendingPreserved);r.put("queued_activated",queuedActivated);r.put("post_release_progress",postReleaseProgress);r.put("final_distance",finalDistance);r.put("final_stable_ticks",stableTail);
            r.put("pad_removed",padRemoved);r.put("pad_blocks",pad.size());r.put("fall_land_ticks",fallLandTicks);r.put("freeflight_samples",freeFlightSamples);r.put("minimum_y",Double.isFinite(minimumY)?minimumY:null);
            r.put("partial_pad_removal_tick",partialRemovalTick);r.put("partial_pad_restore_tick",partialRestoreTick);r.put("partial_pad_blocks",partialPad.size());
            r.put("anchor",anchor);r.put("limit",limit);r.put("minimum_up",minUp);r.put("nonfinite_fields",nonfinite);r.put("metadata",fixture.metadata);
            r.put("terrain_assist_impulses",rig==null?0:rig.terrainAssistImpulses);r.put("terrain_assist_delta_total",rig==null?0:rig.terrainAssistDeltaTotal);
            r.put("terrain_assist_max_horizontal",rig==null?0:rig.terrainAssistMaximumHorizontal);r.put("terrain_assist_max_vertical",rig==null?0:rig.terrainAssistMaximumVertical);
            r.put("assist_actual_com_max_horizontal",actualAssistMaxHorizontal);r.put("assist_actual_com_max_vertical",actualAssistMaxVertical);
            r.put("assist_max_body_delta_disagreement",maximumBodyDeltaDisagreement);r.put("rough_assists",roughAssists);r.put("assist_applications",assists);
            r.put("unassisted_flight_land_samples",unassistedFlightSamples);r.put("unassisted_hold_samples",unassistedHoldSamples);r.put("unassisted_planning_samples",unassistedPlanningSamples);
            r.put("explicit_stop_tick",stopTick);r.put("stopped_samples",stoppedSamples);
            r.put("history",history);r.put("inputs",inputs);r.put("leap_events",nav==null?List.of():nav.leap.events);r.put("native_impulses",rig==null?List.of():rig.leapImpulses);return r;
        }
        void close(){if(closed)return;closed=true;fixture.close();if(!fixture.failures.isEmpty())fail("cleanup: "+fixture.failures);}
    }
    static final class Run {
        final GameTestHelper helper;final MinecraftMachinesLeaperPursuitRegressionTests.Run fixtures;final List<Trial> trials=new ArrayList<>();
        MinecraftMachinesLeaperPredatorPolicy policy;int current,previousSubsteps;boolean physicsChanged;
        Run(GameTestHelper helper){this.helper=helper;fixtures=new MinecraftMachinesLeaperPursuitRegressionTests.Run(helper,MODE);}
        void begin() throws Exception{
            require(MinecraftMachinesLeaperLearning.profile().equals("horrific") && Boolean.getBoolean("minecraft_machines.leaperLeap") && Boolean.getBoolean("minecraft_machines.leaperAgile")
                && Integer.getInteger("minecraft_machines.leaperSubsteps",4)==4 && Boolean.parseBoolean(System.getProperty("minecraft_machines.leaperGrip","true")) && Double.parseDouble(System.getProperty("minecraft_machines.leaperBalance","1"))==1,"RequireH/leap/agile/native4/grip/balance1");
            require(Files.isRegularFile(Path.of("leaper_learning",MinecraftMachinesLeaperLearning.reportName("predator-candidate")+".json")),"Explicit genome required");
            require(!System.getProperty("minecraft_machines.leaperImplementationSha256","unrecorded").equals("unrecorded"),"Source snapshot SHA required");
            policy=MinecraftMachinesLeaperPredatorExperiments.candidate();fixtures.policy=policy;
            var system=SubLevelPhysicsSystem.get(helper.getLevel());previousSubsteps=system.getConfig().substepsPerTick;system.getConfig().substepsPerTick=4;system.onConfigUpdated();physicsChanged=true;
            for(int i=0;i<NAMES.length;i++)trials.add(new Trial(this,i));trials.getFirst().initialize();persist();loop();
        }
        void loop(){helper.runAfterDelay(1,()->{try{var t=trials.get(current);t.step();if(t.done){t.close();persist();current++;if(current==trials.size()){close();helper.succeed();return;}trials.get(current).initialize();}loop();}
            catch(Exception e){for(var t:trials)if(!t.done)t.fail("harness: "+e);close();try{persist();}catch(Exception ignored){}helper.fail(e.toString());}});}
        void persist() throws Exception{
            var r=new LinkedHashMap<String,Object>();r.put("mode",MODE);r.put("qualified",false);r.put("fixture_version","landing-handoff-v3-assist-partial-pad");r.put("expected_cases",NAMES.length);r.put("complete",trials.stream().allMatch(t->t.done));
            r.put("fixture_revision","Prior landing-03 retained: adaptive pose landed3feet on uneven fixture, so partial-support coverage now has a separate physical pad-loss case; original natural uneven geometry retained");
            r.put("purpose","Prompt predator contact handoff, queued-goal progress and no freefall release; user superseded strict15-sample gameplay requirement; no controller state injection");
            r.put("handoff_contract",Map.of("minimum_contact_land_ticks",2,"maximum_usable_contact_delay_ticks",6,"minimum_quiet_land_ticks",10,"minimum_quiet_samples",8));
            r.put("support_measurement","Native toe/surface proximity estimate, not contact-force proof");
            r.put("legacy_timestamp_semantics","stable_landing_tick records usable locomotion handoff in this source; strict sample counts are diagnostic only");
            r.put("control_hz",20);r.put("max_case_ticks",LIMIT);r.put("genome",policy.genome);r.put("parameters",policy.named());r.put("physics_config",SubLevelPhysicsSystem.get(helper.getLevel()).getConfig());r.put("trials",trials.stream().map(Trial::result).toList());
            var invalid=new ArrayList<String>();var safe=MinecraftMachinesLeaperPursuitRegressionTests.clean(MinecraftMachinesLeaperPursuitRegressionTests.JSON.toJsonTree(r),"$",invalid).getAsJsonObject();safe.add("serialization_nonfinite_fields",MinecraftMachinesLeaperPursuitRegressionTests.JSON.toJsonTree(invalid));
            if(!invalid.isEmpty())for(var t:safe.getAsJsonArray("trials"))t.getAsJsonObject().addProperty("success",false);MinecraftMachinesLeaperLearning.write(MODE,safe);
        }
        void close(){for(var t:trials)t.close();if(physicsChanged){var system=SubLevelPhysicsSystem.get(helper.getLevel());system.getConfig().substepsPerTick=previousSubsteps;system.onConfigUpdated();physicsChanged=false;}}
    }
}
