/*
 * Copyright (c) 2016 the original author or authors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.buildship.core.workspace.internal;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.gradle.tooling.CancellationToken;
import org.gradle.tooling.ProgressListener;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.gradleware.tooling.toolingclient.LaunchableConfig;
import com.gradleware.tooling.toolingclient.SingleBuildRequest;
import com.gradleware.tooling.toolingmodel.OmniEclipseProject;
import com.gradleware.tooling.toolingmodel.OmniEclipseProjectNature;
import com.gradleware.tooling.toolingmodel.OmniProjectTask;
import com.gradleware.tooling.toolingmodel.repository.FixedRequestAttributes;
import com.gradleware.tooling.toolingmodel.repository.TransientRequestAttributes;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import org.eclipse.buildship.core.CorePlugin;
import org.eclipse.buildship.core.console.ProcessStreams;
import org.eclipse.buildship.core.util.progress.DelegatingProgressListener;

/**
 * Runs extra tasks that set up the project so it can be used in Eclipse.
 *
 * At some point users will be able to specify these tasks directly in their Gradle build. For now
 * we only have hard coded support for detecting WTP projects, for which we run the 'eclipseWtp'
 * task. That task only behaves correctly on Gradle >= 3.0, so we don't run it on older versions.
 */
public class RunOnImportTasksOperation {

    private static final String WTP_TASK = "eclipseWtp";
    private static final String WTP_COMPONENT_NATURE = "org.eclipse.wst.common.modulecore.ModuleCoreNature";

    private final FixedRequestAttributes build;
    private final Set<OmniEclipseProject> allprojects;

    public RunOnImportTasksOperation(Set<OmniEclipseProject> allProjects, FixedRequestAttributes build) {
        this.allprojects = ImmutableSet.copyOf(allProjects);
        this.build = Preconditions.checkNotNull(build);
    }

    public void run(IProgressMonitor monitor, CancellationToken token) throws CoreException {
        Set<String> tasksToRun = findWtpTasks();
        if (!tasksToRun.isEmpty()) {
            runTasks(tasksToRun, monitor, token);
        }
    }

    private Set<String> findWtpTasks() {
        if (!CorePlugin.workspaceOperations().isNatureRecognizedByEclipse(WTP_COMPONENT_NATURE)) {
            return Collections.emptySet();
        }
        Set<String> tasksToRun = Sets.newHashSet();
        for (OmniEclipseProject eclipseProject : this.allprojects) {
            if (isGradle30(eclipseProject) && isWtpProject(eclipseProject)) {
                List<OmniProjectTask> tasks = eclipseProject.getGradleProject().getProjectTasks();
                for (OmniProjectTask task : tasks) {
                    if (WTP_TASK.equals(task.getName())) {
                        tasksToRun.add(task.getPath().getPath());
                    }
                }
            }
        }
        return tasksToRun;
    }

    private boolean isGradle30(OmniEclipseProject eclipseProject) {
        return eclipseProject.getClasspathContainers().isPresent();
    }

    private boolean isWtpProject(OmniEclipseProject eclipseProject) {
        Optional<List<OmniEclipseProjectNature>> natures = eclipseProject.getProjectNatures();
        if (natures.isPresent()) {
            for (OmniEclipseProjectNature nature : natures.get()) {
                if (nature.getId().equals(WTP_COMPONENT_NATURE)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void runTasks(Set<String> tasksToRun, IProgressMonitor monitor, CancellationToken token) {
        SingleBuildRequest<Void> request = CorePlugin.toolingClient().newBuildLaunchRequest(LaunchableConfig.forTasks(tasksToRun));
        this.build.apply(request);
        getTransientRequestAttributes(token, monitor).apply(request);
        request.executeAndWait();
    }

    private TransientRequestAttributes getTransientRequestAttributes(CancellationToken token, IProgressMonitor monitor) {
        ProcessStreams streams = CorePlugin.processStreamsProvider().getBackgroundJobProcessStreams();
        List<ProgressListener> progressListeners = ImmutableList.<ProgressListener> of(DelegatingProgressListener.withoutDuplicateLifecycleEvents(monitor));
        ImmutableList<org.gradle.tooling.events.ProgressListener> noEventListeners = ImmutableList.<org.gradle.tooling.events.ProgressListener> of();
        return new TransientRequestAttributes(false, streams.getOutput(), streams.getError(), streams.getInput(), progressListeners, noEventListeners, token);
    }

}
