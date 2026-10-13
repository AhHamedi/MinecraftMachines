package dev.ahmedhamedi.minecraft_machines.neoforge;

import com.google.gson.JsonElement;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Three serial native follow checks. Synthetic player positions call the real pursue(), never rig assists. */
@GameTestHolder("minecraft_machines_quadruped_leaper_learning")
@PrefixGameTestTemplate(false)
public class MinecraftMachinesLeaperFollowTests {
    static final String MODE = "predator-follow-regression";
    static final int LIMIT = 1200, FINAL_WATCH = 100;
    static final String[] NAMES = {"moving-flat-super", "moving-up12-horrific", "stationary-jumping-player"};

    @GameTest(template = "empty", timeoutTicks = 4000, batch = "leaperFollowRegression")
    public static void follow(GameTestHelper helper) throws Exception {
        if (!MinecraftMachinesLeaperLearning.mode().equals(MODE)) { helper.succeed(); return; }
        var run = new Run(helper);
        try { run.begin(); }
        catch (Exception e) { run.close(); throw e; }
    }
    static void require(boolean ok, String why) { if (!ok) throw new IllegalStateException(why); }
    static double[] xyz(Vector3d v) { return new double[]{v.x,v.y,v.z}; }
    static Object nullable(Vector3d v) { return v == null ? null : xyz(v); }
    static double horizontal(Vector3d a, Vector3d b) { return Math.hypot(a.x-b.x,a.z-b.z); }

    static final class Trial {
        final Run run; final int id;
        final MinecraftMachinesLeaperPursuitRegressionTests.Trial fixture;
        final List<JsonElement> history = new ArrayList<>(), inputs = new ArrayList<>();
        final List<String> failures = new ArrayList<>(), nonfinite = new ArrayList<>();
        MinecraftMachinesLeaper rig; MinecraftMachinesLeaperPredatorNavigator nav;
        Vector3d start, player, finalPlayer, lastPosition;
        int tick, updates, refused, followPlans, searchReplacements, preparationUpdates, airborneUpdates;
        int preservedAirUpdates, stableTail, longestStable, entryTick = -1, planningRun, longestPlanning;
        int firstLaunch = -1, movementFreezeTick, pendingChanges, zeroSupportArrivals;
        boolean attempted, done, closed, success;
        double movingProgress, closestMovingError = Double.POSITIVE_INFINITY;
        double anchor, limit, minUp = 1, maxPursueMillis, maxStepMillis;
        double maxPostFreezeError, finalDistance;

        Trial(Run run, int id) {
            this.run=run; this.id=id;
            // Reuse only the existing, verified disposable arena/mass/settings setup.
            // No call to the fixed-point fixture's step(), go(), sample() or finish().
            fixture = new MinecraftMachinesLeaperPursuitRegressionTests.Trial(run.fixtures, id==0?3:id==1?4:0);
        }
        void initialize() {
            attempted=true; fixture.initialize();
            if(fixture.done) { fail("fixture: "+fixture.failures); return; }
            rig=fixture.rig; nav=fixture.nav;
            nav.pace=id==0?"super_horrific":"horrific";
            start=new Vector3d(rig.corePivot());
            movementFreezeTick=id==0?281:id==1?241:81;
            double endD=id==0?84:id==1?96:12;
            finalPlayer=new Vector3d(fixture.origin.x,id==1?19:7,fixture.origin.z-endD);
            require(Math.abs(nav.map.height(finalPlayer.x,finalPlayer.z,finalPlayer.y+.55,72)-finalPlayer.y)<1e-6,"Final player floor mismatch");
        }
        void fail(String reason) { failures.add(reason); done=true; success=false; }
        Vector3d nextPlayer() {
            int update=Math.max(0,(tick-81)/10);
            double d=id==0?24+3*Math.min(20,update):id==1?48+3*Math.min(16,update):12;
            // Deterministic alternating player feet height; native support remains unchanged.
            double jump=update%2==1?1.2:0;
            return new Vector3d(fixture.origin.x,(id==1?19:7)+jump,fixture.origin.z-d);
        }
        void update() {
            player=nextPlayer();
            var planningBefore=nav.planning;
            var stateBefore=nav.leap.state;
            var activeBefore=nav.leap.active;
            var pendingBefore=nav.pendingGoal==null?null:new Vector3d(nav.pendingGoal);
            boolean wasAir=nav.leap.inAir();
            int plansBefore=nav.plans, cooldownBefore=nav.leap.cooldown;
            long began=System.nanoTime(); boolean accepted=false;
            try { accepted=nav.pursue(player.x,player.y,player.z); }
            finally { maxPursueMillis=Math.max(maxPursueMillis,(System.nanoTime()-began)/1e6); }
            updates++; if(!accepted) refused++;
            followPlans+=nav.plans-plansBefore;
            if(planningBefore!=null && nav.planning!=planningBefore) searchReplacements++;
            if(!wasAir && stateBefore!=MinecraftMachinesLeaperLeap.State.WALK) {
                preparationUpdates++;
                require(nav.leap.state==stateBefore && nav.leap.active==activeBefore,"Follow canceled current preparation/retreat");
            }
            if(wasAir) {
                airborneUpdates++;
                boolean preserved=nav.leap.active==activeBefore && nav.leap.state==stateBefore;
                if(preserved) preservedAirUpdates++;
                require(preserved,"Airborne follow changed the committed trajectory");
                if(nav.pendingGoal!=null && (pendingBefore==null || nav.pendingGoal.distanceSquared(pendingBefore)>1e-8)) pendingChanges++;
            }
            require(nav.leap.cooldown==cooldownBefore,"Follow refresh postponed takeoff cooldown");
            var row=new LinkedHashMap<String,Object>();
            row.put("tick",tick); row.put("player",xyz(player)); row.put("returned",accepted);
            row.put("state_before",stateBefore.toString()); row.put("state_after",nav.leap.state.toString());
            row.put("plans_before",plansBefore); row.put("plans_after",nav.plans);
            row.put("planning_before",planningBefore!=null); row.put("search_replaced",planningBefore!=null && nav.planning!=planningBefore);
            row.put("cooldown_before",cooldownBefore); row.put("cooldown_after",nav.leap.cooldown);
            row.put("goal",nullable(nav.goal)); row.put("pending_goal",nullable(nav.pendingGoal));
            inputs.add(MinecraftMachinesLeaperPursuitRegressionTests.clean(MinecraftMachinesLeaperPursuitRegressionTests.JSON.toJsonTree(row),"input"+tick,nonfinite));
        }
        void step() {
            if(done) return;
            tick++;
            try {
                // Preparation can complete between two ten-tick observations. Exercise
                // a follow update in that phase explicitly, retaining the same prey path.
                boolean preparing = nav.leap.state==MinecraftMachinesLeaperLeap.State.ALIGN
                        || nav.leap.state==MinecraftMachinesLeaperLeap.State.CROUCH
                        || nav.leap.state==MinecraftMachinesLeaperLeap.State.RETREAT;
                if(tick>=81 && ((tick-81)%10==0 || preparing && preparationUpdates==0)) update();
                long began=System.nanoTime();
                try {nav.step();} finally {maxStepMillis=Math.max(maxStepMillis,(System.nanoTime()-began)/1e6);}
                sample();
                if(done)return;
                if(entryTick>=0 && tick-entryTick>=FINAL_WATCH && stableTail>=40) finish();
                else if(tick>=LIMIT) { failures.add("Timeout: latest stationary player not approached and held"); finish(); }
            } catch(Exception e) {fail("native follow: "+e);}
        }
        void sample() {
            var p=rig.corePivot(); var v=rig.velocity(); lastPosition=new Vector3d(p);
            int supports=nav.leap.supports();
            if(firstLaunch<0 && nav.leap.launches>0) firstLaunch=tick;
            planningRun=nav.planning!=null?planningRun+1:0; longestPlanning=Math.max(longestPlanning,planningRun);
            anchor=Math.max(anchor,rig.anchorError()); limit=Math.max(limit,rig.limitViolation()); minUp=Math.min(minUp,rig.upright());
            if(tick==movementFreezeTick) movingProgress=start.z-p.z;
            if(player!=null && tick<=movementFreezeTick) closestMovingError=Math.min(closestMovingError,horizontal(p,player));
            finalDistance=horizontal(p,finalPlayer);
            boolean matchingFloor=Math.abs(p.y-finalPlayer.y-run.policy.value[4])<1.25;
            // Stop the target, not the walker. Continue sending every10ticks throughout observation.
            if(tick>=movementFreezeTick+20) {
                if(entryTick<0 && finalDistance<=3 && matchingFloor) entryTick=tick;
                if(entryTick>=0) {
                    maxPostFreezeError=Math.max(maxPostFreezeError,finalDistance);
                    boolean stable=finalDistance<=3 && matchingFloor && Math.hypot(v.x,v.z)<1 && Math.abs(v.y)<.75 && rig.upright()>.65;
                    stableTail=stable?stableTail+1:0; longestStable=Math.max(longestStable,stableTail);
                }
            }
            if(nav.arrived && supports==0)zeroSupportArrivals++;
            var row=new LinkedHashMap<String,Object>();
            row.put("tick",tick); row.put("core",xyz(p)); row.put("velocity",xyz(v));
            row.put("goal",nullable(nav.goal)); row.put("pending_goal",nullable(nav.pendingGoal));
            row.put("player",nullable(player)); row.put("final_player",xyz(finalPlayer)); row.put("final_player_distance",finalDistance);
            row.put("planning",nav.planning!=null); row.put("plans",nav.plans); row.put("follow_plan_tick",nav.followPlanTick);
            row.put("state",nav.leap.state.toString()); row.put("state_ticks",nav.leap.stateTicks);
            row.put("launches",nav.leap.launches); row.put("cooldown",nav.leap.cooldown);
            row.put("arrived",nav.arrived); row.put("hold",nav.holdPosition); row.put("supports_proximity_estimate",supports);
            row.put("heading",rig.heading()); row.put("command",nav.command); row.put("super_drive",nav.control.superDrive);
            row.put("anchor",rig.anchorError()); row.put("limit",rig.limitViolation()); row.put("up",rig.upright());
            row.put("angles",rig.angles()); row.put("bounded_angles",rig.boundedMotorTargets());
            var toes=new ArrayList<Object>(); for(int i=0;i<4;i++)toes.add(xyz(rig.toe(i))); row.put("toes",toes);
            history.add(MinecraftMachinesLeaperPursuitRegressionTests.clean(MinecraftMachinesLeaperPursuitRegressionTests.JSON.toJsonTree(row),"tick"+tick,nonfinite));
            if(!rig.valid() || !p.isFinite() || !v.isFinite() || !nonfinite.isEmpty() || anchor>.12 || limit>.15 || rig.upright()<.45)
                fail("Native physical/finite guard failed");
        }
        void finish() {
            done=true;
            if(updates<12)failures.add("Insufficient repeated follow updates");
            if(refused>0)failures.add("Supported synthetic player update rejected");
            if(searchReplacements>0)failures.add("An active search was replaced by follow");
            if(longestPlanning>200)failures.add("Planning starvation: more than200 consecutive held search ticks");
            if(id!=2 && movingProgress<8)failures.add("Less than8blocks forward progress while target was moving");
            if(id==1 && (firstLaunch<0 || firstLaunch>=movementFreezeTick || airborneUpdates<2 || pendingChanges<1 || preservedAirUpdates!=airborneUpdates || preparationUpdates<1))
                failures.add("Moving elevated preparation/launch/airborne latest-target queue not all exercised");
            if(id==2 && followPlans!=1)failures.add("Stationary jumping Y triggered additional follow plans");
            if(entryTick<0 || tick-entryTick<FINAL_WATCH || stableTail<40)failures.add("Missing five-second observation / final two-second settled arrival at actual player");
            success=failures.isEmpty() && nonfinite.isEmpty();
        }
        Object result() {
            var r=new LinkedHashMap<String,Object>();
            r.put("case",NAMES[id]); r.put("attempted",attempted);r.put("complete",done);r.put("success",success);r.put("failures",failures);
            r.put("ticks",tick);r.put("updates",updates);r.put("rejected_updates",refused);r.put("follow_plans",followPlans);r.put("active_search_replacements",searchReplacements);
            r.put("preparation_updates",preparationUpdates);r.put("airborne_updates",airborneUpdates);r.put("preserved_airborne_updates",preservedAirUpdates);r.put("airborne_pending_changes",pendingChanges);
            r.put("movement_freeze_tick",movementFreezeTick);r.put("progress_during_target_motion",movingProgress);r.put("longest_planning_ticks",longestPlanning);r.put("first_launch_tick",firstLaunch);
            r.put("entry_tick",entryTick);r.put("final_stable_ticks",stableTail);r.put("final_player_distance",finalDistance);r.put("final_player",nullable(finalPlayer));r.put("final_position",nullable(lastPosition));
            r.put("max_pursue_millis",maxPursueMillis);r.put("max_step_millis",maxStepMillis);r.put("anchor",anchor);r.put("limit",limit);r.put("minimum_up",minUp);
            r.put("arrived_zero_support_estimate_ticks",zeroSupportArrivals);r.put("nonfinite_fields",nonfinite);r.put("metadata",fixture.metadata);
            r.put("history",history);r.put("inputs",inputs);r.put("leap_events",nav==null?List.of():nav.leap.events);r.put("native_impulses",rig==null?List.of():rig.leapImpulses);
            return r;
        }
        void close() { if(closed)return;closed=true;fixture.close(); if(!fixture.failures.isEmpty())fail("fixture cleanup: "+fixture.failures); }
    }
    static final class Run {
        final GameTestHelper helper; final MinecraftMachinesLeaperPursuitRegressionTests.Run fixtures;
        final List<Trial> trials=new ArrayList<>();
        MinecraftMachinesLeaperPredatorPolicy policy;int current,previousSubsteps;boolean physicsChanged;
        Run(GameTestHelper helper) {this.helper=helper;fixtures=new MinecraftMachinesLeaperPursuitRegressionTests.Run(helper,MODE);}
        void begin() throws Exception {
            require(MinecraftMachinesLeaperLearning.profile().equals("horrific") && Boolean.getBoolean("minecraft_machines.leaperLeap") && Boolean.getBoolean("minecraft_machines.leaperAgile")
                && Integer.getInteger("minecraft_machines.leaperSubsteps",4)==4 && Boolean.parseBoolean(System.getProperty("minecraft_machines.leaperGrip","true"))
                && Double.parseDouble(System.getProperty("minecraft_machines.leaperBalance","1"))==1,"Need explicit H/leap/agile/native4/grip/balance1");
            require(Files.isRegularFile(Path.of("leaper_learning",MinecraftMachinesLeaperLearning.reportName("predator-candidate")+".json")),"Explicit genome required");
            require(!System.getProperty("minecraft_machines.leaperImplementationSha256","unrecorded").equals("unrecorded"),"Frozen source SHA required");
            policy=MinecraftMachinesLeaperPredatorExperiments.candidate();fixtures.policy=policy;
            var system=SubLevelPhysicsSystem.get(helper.getLevel());previousSubsteps=system.getConfig().substepsPerTick;system.getConfig().substepsPerTick=4;system.onConfigUpdated();physicsChanged=true;
            for(int i=0;i<3;i++)trials.add(new Trial(this,i));trials.getFirst().initialize();persist();loop();
        }
        void loop() {
            helper.runAfterDelay(1,()->{
                try {
                    var t=trials.get(current);t.step();
                    if(t.done) {t.close();persist();current++;if(current==trials.size()){close();helper.succeed();return;}trials.get(current).initialize();}
                    loop();
                }catch(Exception e){for(var t:trials)if(!t.done)t.fail("harness: "+e);close();try{persist();}catch(Exception ignored){}helper.fail(e.toString());}
            });
        }
        void persist() throws Exception {
            var r=new LinkedHashMap<String,Object>();r.put("mode",MODE);r.put("qualified",false);r.put("fixture_version","follow-v1");
            r.put("purpose","Three serial native real-pursue checks, synthetic moving/jumping player coordinates every10ticks. No stick event or rendering claim.");
            r.put("expected_cases",3);r.put("complete",trials.stream().allMatch(t->t.done));r.put("control_hz",20);r.put("max_case_ticks",LIMIT);
            r.put("genome",policy.genome);r.put("parameters",policy.named());r.put("physics_config",SubLevelPhysicsSystem.get(helper.getLevel()).getConfig());r.put("trials",trials.stream().map(Trial::result).toList());
            var invalid=new ArrayList<String>();var safe=MinecraftMachinesLeaperPursuitRegressionTests.clean(MinecraftMachinesLeaperPursuitRegressionTests.JSON.toJsonTree(r),"$",invalid).getAsJsonObject();
            safe.add("serialization_nonfinite_fields",MinecraftMachinesLeaperPursuitRegressionTests.JSON.toJsonTree(invalid));
            if(!invalid.isEmpty())for(var t:safe.getAsJsonArray("trials"))t.getAsJsonObject().addProperty("success",false);
            MinecraftMachinesLeaperLearning.write(MODE,safe);
        }
        void close() {for(var t:trials)t.close();if(physicsChanged){var system=SubLevelPhysicsSystem.get(helper.getLevel());system.getConfig().substepsPerTick=previousSubsteps;system.onConfigUpdated();physicsChanged=false;}}
    }
}
