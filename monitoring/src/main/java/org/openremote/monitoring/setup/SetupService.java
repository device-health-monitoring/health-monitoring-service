/*
 * Copyright 2016, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.monitoring.setup;

import org.openremote.container.persistence.PersistenceService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.setup.Setup;
import org.openremote.model.setup.SetupTasks;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Logger;


public class SetupService implements ContainerService {

    private static final Logger LOG = Logger.getLogger(SetupService.class.getName());

    final protected List<Setup> tasks = new ArrayList<>();

    @Override
    public int getPriority() {
        return PersistenceService.PRIORITY + 10; // Start just after persistence service
    }

    @Override
    public void init(Container container) throws Exception {

        boolean isClean = container.getService(PersistenceService.class).isCleanInstall();

        if (!isClean) {
            LOG.info("Setup service disabled, clean install = false");
            return;
        }

        // If keycloak then we need keycloak clean and init tasks
        String setupType = container.getConfig().get(SetupTasks.OR_SETUP_TYPE);


        tasks.addAll(ServiceLoader.load(SetupTasks.class).stream().map(ServiceLoader.Provider::get)
            .flatMap(discoveredSetupTasks -> {
                LOG.info("Found custom SetupTasks provider on classpath: " + discoveredSetupTasks.getClass().getName());
                LOG.info("Custom SetupTasks provider task count for setupType '" + setupType + "' = " + (tasks == null ? 0 : tasks.size()));
                return tasks != null ? tasks.stream() : null;
            }).toList());

        try {
            if (tasks.size() > 0) {
                LOG.info("--- EXECUTING INIT TASKS ---");
                for (Setup setup : tasks) {
                    LOG.info("Executing setup task '" + setup.getClass().getName() + "'");
                    setup.onInit();
                }
                LOG.info("--- INIT TASKS COMPLETED SUCCESSFULLY ---");
            }
        } catch (Exception ex) {
            throw new RuntimeException("Error setting up application", ex);
        }
    }

    @Override
    public void start(Container container) {

        try {
            if (tasks.size() > 0) {
                LOG.info("--- EXECUTING START TASKS ---");
                for (Setup setup : tasks) {
                    LOG.info("Executing setup task '" + setup.getClass().getName() + "'");
                    setup.onStart();
                }
                LOG.info("--- START TASKS COMPLETED SUCCESSFULLY ---");
            }
        } catch (Exception ex) {
            throw new RuntimeException("Error setting up application", ex);
        }
    }

    @Override
    public void stop(Container container) {
        tasks.clear();
    }

    @SuppressWarnings("unchecked")
    public <S extends Setup> S getTaskOfType(Class<S> setupType) {
        for (Setup task : tasks) {
            if (setupType.isAssignableFrom(task.getClass()))
                return (S) task;
        }
        throw new IllegalStateException("No setup task found of type: " + setupType);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
            '}';
    }
}
