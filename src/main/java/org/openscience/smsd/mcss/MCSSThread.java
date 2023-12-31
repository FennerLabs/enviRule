/*
 * Copyright (C) 2014 Syed Asad Rahman <asad at ebi.ac.uk>.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 */
package org.openscience.smsd.mcss;

import static java.lang.Integer.MAX_VALUE;
import static java.util.Calendar.getInstance;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.openscience.cdk.aromaticity.Aromaticity;
import static org.openscience.cdk.aromaticity.ElectronDonation.daylight;
import org.openscience.cdk.exception.CDKException;
import org.openscience.cdk.graph.Cycles;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.smiles.SmiFlavor;
import org.openscience.cdk.smiles.SmilesGenerator;
import org.openscience.cdk.tools.ILoggingTool;
import static org.openscience.cdk.tools.LoggingToolFactory.createLoggingTool;
import org.openscience.smsd.BaseMapping;
import org.openscience.smsd.Isomorphism;
import org.openscience.smsd.algorithm.matchers.AtomBondMatcher;
import org.openscience.smsd.algorithm.matchers.AtomMatcher;
import org.openscience.smsd.algorithm.matchers.BondMatcher;
import static org.openscience.smsd.interfaces.Algorithm.DEFAULT;
import static org.openscience.smsd.mcss.JobType.MULTIPLE;
import static org.openscience.smsd.tools.ExtAtomContainerManipulator.removeHydrogens;

/**
 *
 *
 * @author Syed Asad Rahman <asad @ ebi.ac.uk>
 *
 */
public class MCSSThread implements Callable<LinkedBlockingQueue<IAtomContainer>> {

    private final static ILoggingTool LOGGER
            = createLoggingTool(MCSSThread.class);
    private final List<IAtomContainer> mcssList;
    private final JobType jobType;
    private final int taskNumber;
    private final AtomMatcher atomMatcher;
    private final BondMatcher bondMatcher;

    /**
     *
     * @param mcssList
     * @param jobType MULTIPLE/SINGLE
     * @param taskNumber
     */
    public MCSSThread(List<IAtomContainer> mcssList, JobType jobType, int taskNumber) {
        this(mcssList, jobType, taskNumber,
                AtomBondMatcher.atomMatcher(true, true),
                AtomBondMatcher.bondMatcher(true, true)
        );
    }

    /**
     *
     * @param mcssList
     * @param jobType
     * @param taskNumber
     * @param am
     * @param bm
     */
    public MCSSThread(List<IAtomContainer> mcssList, JobType jobType, int taskNumber,
            AtomMatcher am, BondMatcher bm) {
        this.mcssList = mcssList;
        this.jobType = jobType;
        this.taskNumber = taskNumber;
        this.atomMatcher = am;
        this.bondMatcher = bm;

    }

    @Override
    public synchronized LinkedBlockingQueue<IAtomContainer> call() {
        if (this.jobType.equals(MULTIPLE)) {
            return multiSolution();
        } else {
            return singleSolution();
        }
    }

    /*
     * MULTIPLE Fragments of MCS are returned if present
     */
    private synchronized LinkedBlockingQueue<IAtomContainer> multiSolution() {
        /*
         * Store final solution here
         */
        LinkedBlockingQueue<IAtomContainer> mcss = new LinkedBlockingQueue<>();

        LOGGER.debug("Calling MCSSTask " + taskNumber + " with " + mcssList.size() + " items");
        long startTime = getInstance().getTimeInMillis();
        IAtomContainer querySeed = mcssList.get(0);
        long calcTime = startTime;

        ConcurrentLinkedQueue<IAtomContainer> seeds = new ConcurrentLinkedQueue<>();
        try {
            /*
             * Local Seeds
             */
            Set<Fragment> localSeeds = new TreeSet<>();
            int minSeedSize = querySeed.getAtomCount();

            for (int index = 1; index < mcssList.size(); index++) {
                IAtomContainer target = mcssList.get(index);
                Collection<Fragment> fragmentsFromMCS;
                BaseMapping comparison;
                comparison = new Isomorphism(querySeed, target, DEFAULT, atomMatcher, bondMatcher);
                comparison.setChemFilters(true, true, true);
                fragmentsFromMCS = getMCSS(comparison);

                LOGGER.debug("comparison for task " + taskNumber + " has " + fragmentsFromMCS.size()
                        + " unique matches of size " + comparison.getFirstAtomMapping().getCount());
                LOGGER.debug("MCSS for task " + taskNumber + " has " + querySeed.getAtomCount() + " atoms, and " + querySeed.getBondCount() + " bonds");
                LOGGER.debug("Target for task " + taskNumber + " has " + target.getAtomCount() + " atoms, and " + target.getBondCount() + " bonds");
                long endCalcTime = getInstance().getTimeInMillis();
                LOGGER.debug("Task " + taskNumber + " index " + index + " took " + (endCalcTime - calcTime) + "ms");
                calcTime = endCalcTime;

                if (fragmentsFromMCS.isEmpty()) {
                    localSeeds.clear();
                    break;
                }
                Iterator<Fragment> iterator = fragmentsFromMCS.iterator();
                /*
                 * Store rest of the unique hits
                 */
                while (iterator.hasNext()) {
                    Fragment fragment = iterator.next();
                    if (minSeedSize > fragment.getContainer().getAtomCount()) {
                        localSeeds.clear();
                        minSeedSize = fragment.getContainer().getAtomCount();
                    }
                    if (minSeedSize == fragment.getContainer().getAtomCount()) {
                        localSeeds.add(fragment);
                    }
                }
            }
            /*
             * Add all the Maximum Unique Substructures
             */
            if (!localSeeds.isEmpty()) {
                for (Fragment f : localSeeds) {
                    seeds.add(f.getContainer());
                }
                localSeeds.clear();
            }

            LOGGER.debug("No of Potential MULTIPLE " + seeds.size());

            /*
             * Choose only cleaned MULTIPLE Substructures
             */
            minSeedSize = MAX_VALUE;

            while (!seeds.isEmpty()) {
                IAtomContainer fragmentMCS = seeds.poll();
                localSeeds = new TreeSet<>();
                LOGGER.debug("Potential MULTIPLE " + getMCSSSmiles(fragmentMCS));
                Collection<Fragment> fragmentsFromMCS;
                for (IAtomContainer target : mcssList) {
                    Isomorphism comparison = new Isomorphism(fragmentMCS, target, DEFAULT, atomMatcher, bondMatcher);
                    comparison.setChemFilters(true, true, true);
                    fragmentsFromMCS = getMCSS(comparison);

                    /*
                     * Only true MCSS is added
                     */
                    if (fragmentsFromMCS == null || fragmentsFromMCS.isEmpty()) {
                        localSeeds.clear();
                        break;
                    }
                    Iterator<Fragment> iterator = fragmentsFromMCS.iterator();
                    /*
                     * Store rest of the unique hits
                     */
                    while (iterator.hasNext()) {
                        Fragment fragment = iterator.next();
                        if (minSeedSize > fragment.getContainer().getAtomCount()) {
                            localSeeds.clear();
                            minSeedSize = fragment.getContainer().getAtomCount();
                        }
                        if (minSeedSize == fragment.getContainer().getAtomCount()) {
                            localSeeds.add(fragment);
                        }
                    }
                    /*
                     * Top solution
                     */
                    fragmentMCS = localSeeds.iterator().next().getContainer();
                }

                /*
                 * Add all the Maximum Unique Substructures
                 */
                if (!localSeeds.isEmpty()) {
                    for (Fragment f : localSeeds) {
                        mcss.add(f.getContainer());
                    }
                    localSeeds.clear();
                }

            }
        } catch (CDKException e) {
            LOGGER.error("ERROR IN MCS Thread: ", e.getMessage());
        }
        long endTime = getInstance().getTimeInMillis();
        LOGGER.debug("Done: task " + taskNumber + " took " + (endTime - startTime) + "ms");
        LOGGER.debug(" and mcss has " + querySeed.getAtomCount() + " atoms, and " + querySeed.getBondCount() + " bonds");
        return mcss;
    }

    /*
     * SINGLE Fragment of MCS is returned if present.
     */
    private synchronized LinkedBlockingQueue<IAtomContainer> singleSolution() {

        LOGGER.debug("Calling MCSSTask " + taskNumber + " with " + mcssList.size() + " items");
        LinkedBlockingQueue<IAtomContainer> mcss = new LinkedBlockingQueue<>();
        long startTime = getInstance().getTimeInMillis();
        IAtomContainer querySeed = mcssList.get(0);
        long calcTime = startTime;

        try {
            for (int index = 1; index < mcssList.size(); index++) {
                IAtomContainer target = removeHydrogens(mcssList.get(index));
                Collection<Fragment> fragmentsFomMCS;
                BaseMapping comparison;

                comparison = new Isomorphism(querySeed, target, DEFAULT, atomMatcher, bondMatcher);
                comparison.setChemFilters(true, true, true);
                fragmentsFomMCS = getMCSS(comparison);

                LOGGER.debug("comparison for task " + taskNumber + " has " + fragmentsFomMCS.size()
                        + " unique matches of size " + comparison.getFirstAtomMapping().getCount());
                LOGGER.debug("MCSS for task " + taskNumber + " has " + querySeed.getAtomCount() + " atoms, and " + querySeed.getBondCount() + " bonds");
                LOGGER.debug("Target for task " + taskNumber + " has " + target.getAtomCount() + " atoms, and " + target.getBondCount() + " bonds");
                long endCalcTime = getInstance().getTimeInMillis();
                LOGGER.debug("Task " + taskNumber + " index " + index + " took " + (endCalcTime - calcTime) + "ms");
                calcTime = endCalcTime;

                if (fragmentsFomMCS.isEmpty()) {
                    break;
                }
                querySeed = fragmentsFomMCS.iterator().next().getContainer();
            }

            if (querySeed != null) {
                mcss.add(querySeed);
                long endTime = getInstance().getTimeInMillis();
                LOGGER.debug("Done: task " + taskNumber + " took " + (endTime - startTime) + "ms");
                LOGGER.debug(" and mcss has " + querySeed.getAtomCount() + " atoms, and " + querySeed.getBondCount() + " bonds");
            }
        } catch (Exception e) {
            LOGGER.error("ERROR IN MCS Thread: ", e.getMessage());
        }
        return mcss;
    }

    private synchronized Collection<Fragment> getMCSS(BaseMapping comparison) {
        Set<Fragment> matchList = new HashSet<>();
        comparison.getAllAtomMapping().stream().forEach((mapping) -> {
            IAtomContainer match;
            try {
                match = mapping.getCommonFragment();
                try {
                    matchList.add(new Fragment(match));
                } catch (CDKException ex) {
                    LOGGER.error("ERROR IN MCS Thread: ", ex);
                }
            } catch (CloneNotSupportedException ex) {
                LOGGER.error("ERROR IN MCS Thread: ", ex);
            }
        });
        return matchList;
    }

    /**
     * Return SMILES
     *
     * @param ac
     * @return
     * @throws org.openscience.cdk.exception.CDKException
     */
    public synchronized String getMCSSSmiles(IAtomContainer ac) throws CDKException {
        Aromaticity aromaticity = new Aromaticity(daylight(),
                Cycles.or(Cycles.all(),
                        Cycles.or(Cycles.relevant(),
                                Cycles.essential())));
        SmilesGenerator g = new SmilesGenerator(
                SmiFlavor.Unique
                | SmiFlavor.UseAromaticSymbols
                | SmiFlavor.Stereo);
        aromaticity.apply(ac);
        return g.create(ac);
    }

    /**
     * @return the taskNumber
     */
    public synchronized int getTaskNumber() {
        return taskNumber;
    }
}
