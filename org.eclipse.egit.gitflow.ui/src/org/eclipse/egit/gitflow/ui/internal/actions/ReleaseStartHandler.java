/*******************************************************************************
 * Copyright (C) 2015, Max Hohenegger <eclipse@hohenegger.eu>
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.egit.gitflow.ui.internal.actions;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.PlatformObject;
import org.eclipse.egit.gitflow.GitFlowRepository;
import org.eclipse.egit.gitflow.WrongGitFlowStateException;
import org.eclipse.egit.gitflow.op.ReleaseStartOperation;
import org.eclipse.egit.gitflow.ui.internal.JobUtil;
import org.eclipse.egit.gitflow.ui.internal.UIText;
import org.eclipse.egit.gitflow.ui.internal.validation.ReleaseNameValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revplot.PlotCommit;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.team.ui.history.IHistoryView;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * git flow release start
 */
public class ReleaseStartHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		final GitFlowRepository gfRepo = GitFlowHandlerUtil.getRepository(event);
		final String startCommitSha1 = getStartCommit(event);

		InputDialog inputDialog = new InputDialog(
				HandlerUtil.getActiveShell(event),
				UIText.ReleaseStartHandler_provideReleaseName,
				UIText.ReleaseStartHandler_provideANameForTheNewRelease, "", //$NON-NLS-1$
				new ReleaseNameValidator(gfRepo));

		if (inputDialog.open() != Window.OK) {
			return null;
		}

		final String releaseName = inputDialog.getValue();

		ReleaseStartOperation releaseStartOperation = new ReleaseStartOperation(
				gfRepo, startCommitSha1, releaseName);
		JobUtil.scheduleUserWorkspaceJob(releaseStartOperation,
				UIText.ReleaseStartHandler_startingNewRelease,
				JobUtil.GITFLOW_FAMILY);

		return null;
	}

	private String getStartCommit(ExecutionEvent event)
			throws ExecutionException {
		IStructuredSelection selection = (IStructuredSelection) HandlerUtil
				.getCurrentSelection(event);
		if (selection.getFirstElement() instanceof PlotCommit) {
			RevCommit plotCommit = (RevCommit) selection.getFirstElement();
			return plotCommit.getName();
		} else {
			GitFlowRepository gitFlowRepository = new GitFlowRepository(getRepository(event));
			RevCommit head;
			try {
				head = gitFlowRepository.findHead();
			} catch (WrongGitFlowStateException e) {
				throw new ExecutionException(e.getMessage());
			}
			return head.getName();
		}
	}

	private Repository getRepository(ExecutionEvent event)
			throws ExecutionException {
		PlatformObject firstElement;
		IStructuredSelection selection = (IStructuredSelection) HandlerUtil
				.getCurrentSelection(event);
		if (selection.getFirstElement() instanceof PlotCommit) {
			IWorkbenchPart ap = HandlerUtil.getActivePartChecked(event);
			if (ap instanceof IHistoryView) {
				firstElement = (PlatformObject) ((IHistoryView) ap)
						.getHistoryPage().getInput();
			} else {
				// This is unexpected
				return null;
			}

		} else {
			firstElement = (PlatformObject) selection.getFirstElement();
		}
		return (Repository) firstElement.getAdapter(Repository.class);
	}
}
