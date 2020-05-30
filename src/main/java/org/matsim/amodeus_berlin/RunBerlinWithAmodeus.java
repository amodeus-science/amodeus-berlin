package org.matsim.amodeus_berlin;

import java.util.Random;

import org.matsim.amodeus.config.AmodeusConfigGroup;
import org.matsim.amodeus.config.AmodeusModeConfig;
import org.matsim.amodeus.framework.AmodeusModule;
import org.matsim.amodeus.framework.AmodeusQSimModule;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.QSimConfigGroup.StarttimeInterpretation;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.TripsToLegsAlgorithm;
import org.matsim.core.router.RoutingModeMainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.run.RunBerlinScenario;

public class RunBerlinWithAmodeus {
    static public final String MY_MODE = "mymode";

    static public void main(String[] args) {
        // This run script is based on the Jitpack 5.5.x-SNAPSHOT build of the
        // open Berlin scenario, so it may be subject to change.
        // Also, the config file should be taken from that repository and branch (berlin-v5.5-1pct.config.xml)

        // CONFIG PART

        // Use the config file from the MATSim Berlin *Github repository* (not SVN)
        Config config = RunBerlinScenario.prepareConfig(new String[] { "berlin-v5.5-1pct.config.xml" });

        // Additional config groups
        config.addModule(new DvrpConfigGroup());
        config.addModule(new AmodeusConfigGroup());

        AmodeusConfigGroup amodeusConfig = AmodeusConfigGroup.get(config);

        // Otherwise we need to define AmodeusScorignParmeters for waiting time, price, etc.
        amodeusConfig.setUseScoring(false);

        // Define the mode, like in multimodal DRT
        AmodeusModeConfig modeConfig = new AmodeusModeConfig(MY_MODE);

        // Here, one can choose the different algorithms. Note that for some
        // you will need to install and set up GLPK (but GBM should work out-of-the-box).
        modeConfig.getDispatcherConfig().setType("GlobalBipartiteMatchingDispatcher");

        // Various other options are available
        modeConfig.setPredictRoutePrice(false);
        modeConfig.setPredictRouteTravelTime(false);

        amodeusConfig.addMode(modeConfig);

        // Following, some Amodeus-unrelated settings for scorign and DVRP
        config.planCalcScore().getOrCreateModeParams(MY_MODE);
        config.qsim().setSimStarttimeInterpretation(StarttimeInterpretation.onlyUseStarttime);
        config.controler().setOutputDirectory("simulation_output");
        config.controler().setOverwriteFileSetting(OverwriteFileSetting.deleteDirectoryIfExists);

        // SCENARIO PART

        Scenario scenario = RunBerlinScenario.prepareScenario(config);
        defineInitialDemand(scenario.getPopulation(), MY_MODE, 0.01);

        // CONTROLLER PART

        Controler controller = RunBerlinScenario.prepareControler(scenario);

        controller.addOverridingModule(new DvrpModule());
        controller.addOverridingModule(new AmodeusModule());

        controller.addOverridingQSimModule(new AmodeusQSimModule());
        controller.configureQSimComponents(AmodeusQSimModule.activateModes(config));

        // Note that currently, this will run one iteration and then crash, because
        // there is an OpenBerlinIntermodalPtDrtRouterModeIdentifier, which does not
        // know about MY_MODE.

        controller.run();
    }

    /** Assigns the AMoDeus mode to *all* trips in the plans of X% of the agents.y */
    static public void defineInitialDemand(Population population, String mode, double probability) {
        Random random = new Random(0);
        TripsToLegsAlgorithm tripsToLegs = new TripsToLegsAlgorithm(new RoutingModeMainModeIdentifier());

        for (Person person : population.getPersons().values()) {
            if (PopulationUtils.getSubpopulation(person).equals("person")) {
                if (random.nextDouble() < probability) {
                    for (Plan plan : person.getPlans()) {
                        tripsToLegs.run(plan);

                        for (Leg leg : PopulationUtils.getLegs(plan)) {
                            leg.setMode(mode);
                            TripStructureUtils.setRoutingMode(leg, mode);
                        }
                    }
                }
            }
        }
    }
}
