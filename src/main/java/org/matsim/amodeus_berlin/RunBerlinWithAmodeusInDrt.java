package org.matsim.amodeus_berlin;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.matsim.amodeus.config.AmodeusConfigGroup;
import org.matsim.amodeus.config.AmodeusModeConfig;
import org.matsim.amodeus.drt.AmodeusDrtModule;
import org.matsim.amodeus.drt.AmodeusDrtQSimModule;
import org.matsim.amodeus.drt.MultiModeDrtModuleForAmodeus;
import org.matsim.amodeus.framework.AmodeusModule;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigGroup.OperationalScheme;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicle;
import org.matsim.contrib.dvrp.fleet.FleetWriter;
import org.matsim.contrib.dvrp.fleet.ImmutableDvrpVehicleSpecification;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.QSimConfigGroup.StarttimeInterpretation;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.run.RunBerlinScenario;

public class RunBerlinWithAmodeusInDrt {
    static public void main(String[] args) {
        // Please first go through RunBerlinWithAmodeus, so you can see what are the differences.

        // This run script is based on the Jitpack 5.5.x-SNAPSHOT build of the
        // open Berlin scenario, so it may be subject to change.
        // Also, the config file should be taken from that repository and branch (berlin-v5.5-1pct.config.xml)

        // CONFIG PART

        // Use the config file from the MATSim Berlin *Github repository* (not SVN)
        Config config = RunBerlinScenario.prepareConfig(new String[] { "berlin-v5.5-1pct.config.xml" });

        // Add DRT (I tried to use the berlin-drt-config from the repository, but I get unmaterialized config group "drtfares"

        config.addModule(new DvrpConfigGroup());

        MultiModeDrtConfigGroup drtConfig = new MultiModeDrtConfigGroup();
        config.addModule(drtConfig);

        DrtConfigGroup drtModeConfig = new DrtConfigGroup();
        drtModeConfig.setMode("drt");

        drtModeConfig.setMaxTravelTimeBeta(600.0);
        drtModeConfig.setMaxTravelTimeAlpha(1.4);
        drtModeConfig.setMaxWaitTime(600.0);
        drtModeConfig.setStopDuration(60);
        drtModeConfig.setRejectRequestIfMaxWaitOrTravelTimeViolated(true);
        drtModeConfig.setOperationalScheme(OperationalScheme.door2door);
        
        // We will create a fleet further below
        drtModeConfig.setVehiclesFile("drt_vehicles.xml.gz");

        drtConfig.addParameterSet(drtModeConfig);
        DrtConfigs.adjustDrtConfig(drtModeConfig, config.planCalcScore(), config.plansCalcRoute());

        // Some additional options to run DRT
        config.qsim().setSimStarttimeInterpretation(StarttimeInterpretation.onlyUseStarttime);
        config.controler().setOutputDirectory("simulation_output");
        config.controler().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);
        config.qsim().setNumberOfThreads(1);
        config.planCalcScore().getOrCreateModeParams("drt");

        // Add Amodeus, and disable extended scoring functionality (for waiting times, predicted prices, ...)
        AmodeusConfigGroup amodeusConfig = new AmodeusConfigGroup();
        amodeusConfig.setUseScoring(false);
        config.addModule(amodeusConfig);

        // SCENARIO PART

        Scenario scenario = RunBerlinScenario.prepareScenario(config);
        RunBerlinWithAmodeus.defineInitialDemand(scenario.getPopulation(), "drt", 0.01);

        // Create fleet
        createFleet("drt_vehicles.xml.gz", 100, scenario.getNetwork());
        
        // CONTROLLER PART

        Controler controller = RunBerlinScenario.prepareControler(scenario);

        controller.addOverridingModule(new DvrpModule());
        controller.configureQSimComponents(DvrpQSimComponents.activateModes(drtModeConfig.getMode()));

        // Add DRT, but NOT with MultiModeDrtModule, but with MultiModeDrtModuleForAmodeus
        // because right now we remove DRT's analysis components as they are not compatible yet.
        // In particular, Amodeus makes a lot of use internally ov AmodeusStayTask, etc., while
        // Drt would use DrtStayTask etc. The problem here is that the Drt analysis relies on
        // those tasks, while, currently, all the DVRP dynamics happen with the AmodeusTasks, even
        // if we integrate it to DRT. However, we *can* factor out all the Amodeus*Task from
        // Amodeus, so it can perfectly work with Drt*Task as well. However, this seems to be
        // quite a bit of work, so for now this is only the first step of integration.
        controller.addOverridingModule(new MultiModeDrtModuleForAmodeus());

        // Add overriding modules for the Drt <-> Amodeus integration, which override some
        // components of DRT. Later on, we would only override DrtOptimizer, but we are
        // not there yet, because Amodeus internally still works with AmodeusStayTask, etc.
        // and does not understand DrtStayTask, etc.

        // Note that here we pass a specific AmodeusModeConfig (e.g. mode-specific) with
        // the same mode as defined in DRT. Here we define that DRT will be run with
        // the GlobalBipartiteMatchingDispatcher underneath.

        AmodeusModeConfig modeConfig = new AmodeusModeConfig("drt");
        modeConfig.getDispatcherConfig().setType("GlobalBipartiteMatchingDispatcher");

        controller.addOverridingModule(new AmodeusModule()); 
        controller.addOverridingModule(new AmodeusDrtModule(modeConfig));
        controller.addOverridingQSimModule(new AmodeusDrtQSimModule(modeConfig.getMode()));

        controller.run();

        // So we have some TODOs for the Amodeus / DRT integration
        // - Refactor Amodeus to not be reliant on Amodeus*Task anymore. In fact, all
        // of the related functionality could be hidden behind the RoboTaxi interface.
        // - AmodeusDrtModule and AmodeusDrtQSimModule are mainly copies of their non-drt
        // versions, only with some unncessary components removed. This can be streamlined
        // by composing the "standard" AmodeusModule with all the scoring, pricing,
        // waiting time, etc. functionality with a partial module which contains all the
        // definitons for the dispatching dynamcis.
    }
    
    static public void createFleet(String path, int numberOfVehicles, Network network) {
        Random random = new Random(0);

        List<Link> links = network.getLinks().values().stream().filter(link -> link.getAllowedModes().contains("car")).collect(Collectors.toList());

        new FleetWriter(IntStream.range(0, 100).mapToObj(i -> {
            return ImmutableDvrpVehicleSpecification.newBuilder() //
                    .id(Id.create("drt" + i, DvrpVehicle.class)) //
                    .startLinkId(links.get(random.nextInt(links.size())).getId()) //
                    .capacity(4) //
                    .serviceBeginTime(0.0) //
                    .serviceEndTime(30.0 * 3600.0) //
                    .build();
        })).write(path);
    }
}
