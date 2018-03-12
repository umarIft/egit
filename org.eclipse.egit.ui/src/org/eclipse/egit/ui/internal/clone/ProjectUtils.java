/*******************************************************************************
 * Copyright (C) 2011, 2014 Mathias Kinzler <mathias.kinzler@sap.com> and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.egit.ui.internal.clone;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.egit.core.op.ConnectProviderOperation;
import org.eclipse.egit.core.project.RepositoryFinder;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.egit.ui.internal.UIText;
import org.eclipse.ui.IWorkingSet;
import org.eclipse.ui.IWorkingSetManager;
import org.eclipse.ui.PlatformUI;

/**
 * Utilities for creating (importing) projects
 */
public class ProjectUtils {
	/**
	 * Create (import) a set of existing projects. The projects are
	 * automatically connected to the repository they reside in.
	 *
	 * @param projectsToCreate
	 *            the projects to create
	 * @param selectedWorkingSets
	 *            the workings sets to add the created projects to, may be null
	 *            or empty
	 * @param monitor
	 * @throws InvocationTargetException
	 * @throws InterruptedException
	 */
	public static void createProjects(
			final Set<ProjectRecord> projectsToCreate,
			final IWorkingSet[] selectedWorkingSets, IProgressMonitor monitor)
			throws InvocationTargetException, InterruptedException {
		createProjects(projectsToCreate, false, selectedWorkingSets, monitor);
	}

	/**
	 * Create (import) a set of existing projects. The projects are
	 * automatically connected to the repository they reside in.
	 *
	 * @param projectsToCreate
	 *            the projects to create
	 * @param open
	 *            true to open existing projects, false to leave in current
	 *            state
	 * @param selectedWorkingSets
	 *            the workings sets to add the created projects to, may be null
	 *            or empty
	 * @param monitor
	 * @throws InvocationTargetException
	 * @throws InterruptedException
	 */
	public static void createProjects(
			final Set<ProjectRecord> projectsToCreate, final boolean open,
			final IWorkingSet[] selectedWorkingSets, IProgressMonitor monitor)
			throws InvocationTargetException, InterruptedException {
		IWorkspaceRunnable wsr = new IWorkspaceRunnable() {
			@Override
			public void run(IProgressMonitor actMonitor) throws CoreException {
				IWorkingSetManager workingSetManager = PlatformUI
						.getWorkbench().getWorkingSetManager();
				try {
					actMonitor.beginTask("", projectsToCreate.size() * 2 + 1); //$NON-NLS-1$
					if (actMonitor.isCanceled())
						throw new OperationCanceledException();
					Map<IProject, File> projectsToConnect = new HashMap<IProject, File>();
					for (ProjectRecord projectRecord : projectsToCreate) {
						if (actMonitor.isCanceled())
							throw new OperationCanceledException();
						actMonitor.subTask(projectRecord.getProjectLabel());
						IProject project = createExistingProject(projectRecord,
								open, new SubProgressMonitor(actMonitor, 1));
						if (project == null)
							continue;

						RepositoryFinder finder = new RepositoryFinder(project);
						finder.setFindInChildren(false);
						Collection<RepositoryMapping> mappings = finder
								.find(new SubProgressMonitor(actMonitor, 1));
						if (!mappings.isEmpty()) {
							RepositoryMapping mapping = mappings.iterator()
									.next();
							IPath absolutePath = mapping
									.getGitDirAbsolutePath();
							if (absolutePath != null) {
								projectsToConnect.put(project,
										absolutePath.toFile());
							}
						}

						if (selectedWorkingSets != null
								&& selectedWorkingSets.length > 0)
							workingSetManager.addToWorkingSets(project,
									selectedWorkingSets);
					}

					if (!projectsToConnect.isEmpty()) {
						ConnectProviderOperation connect = new ConnectProviderOperation(
								projectsToConnect);
						connect.execute(new SubProgressMonitor(actMonitor, 1));
					}
				} finally {
					actMonitor.done();
				}
			}
		};
		try {
			ResourcesPlugin.getWorkspace().run(wsr, monitor);
		} catch (OperationCanceledException e) {
			throw new InterruptedException();
		} catch (CoreException e) {
			throw new InvocationTargetException(e);
		}
	}

	private static IProject createExistingProject(final ProjectRecord record,
			final boolean open, IProgressMonitor monitor) throws CoreException {
		String projectName = record.getProjectName();
		final IWorkspace workspace = ResourcesPlugin.getWorkspace();
		final IProject project = workspace.getRoot().getProject(projectName);
		if (project.exists()) {
			if (open && !project.isOpen()) {
				IPath location = project.getFile(
						IProjectDescription.DESCRIPTION_FILE_NAME)
						.getLocation();
				if (location != null
						&& location.toFile().equals(
								record.getProjectSystemFile())) {
					project.open(monitor);
					project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
				}
			}
			return null;
		}
		if (record.getProjectDescription() == null) {
			// error case
			record.setProjectDescription(workspace
					.newProjectDescription(projectName));
			IPath locationPath = new Path(record.getProjectSystemFile()
					.getAbsolutePath());

			// If it is under the root use the default location
			if (Platform.getLocation().isPrefixOf(locationPath))
				record.getProjectDescription().setLocation(null);
			else
				record.getProjectDescription().setLocation(locationPath);
		} else
			record.getProjectDescription().setName(projectName);

		try {
			monitor.beginTask(
					UIText.WizardProjectsImportPage_CreateProjectsTask, 100);
			project.create(record.getProjectDescription(),
					new SubProgressMonitor(monitor, 30));
			project.open(IResource.BACKGROUND_REFRESH, new SubProgressMonitor(
					monitor, 50));
			return project;
		} finally {
			monitor.done();
		}
	}
}