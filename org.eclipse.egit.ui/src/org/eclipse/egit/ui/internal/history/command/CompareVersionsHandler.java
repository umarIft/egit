/*******************************************************************************
 * Copyright (C) 2010, Mathias Kinzler <mathias.kinzler@sap.com>
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.egit.ui.internal.history.command;

import java.util.Iterator;

import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.ITypedElement;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.egit.ui.Activator;
import org.eclipse.egit.ui.UIText;
import org.eclipse.egit.ui.internal.CompareUtils;
import org.eclipse.egit.ui.internal.EgitUiEditorUtils;
import org.eclipse.egit.ui.internal.GitCompareFileRevisionEditorInput;
import org.eclipse.egit.ui.internal.GitCompareFileRevisionEditorInput.EmptyTypedElement;
import org.eclipse.egit.ui.internal.history.GitHistoryPage;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.osgi.util.NLS;
import org.eclipse.team.ui.history.IHistoryView;
import org.eclipse.ui.IWorkbenchPart;

/**
 * Compare the file contents of two commits.
 */
public class CompareVersionsHandler extends AbstractHistoryCommanndHandler {
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IStructuredSelection selection = getSelection(getPage());
		if (selection.size() == 2) {
			Iterator<?> it = selection.iterator();
			RevCommit commit1 = (RevCommit) it.next();
			RevCommit commit2 = (RevCommit) it.next();

			Object input = null;
			IWorkbenchPart part = getPart(event);
			if (part instanceof IHistoryView) {
				input = ((IHistoryView) part).getHistoryPage().getInput();
			}

			if (input != null && input instanceof IFile) {
				IFile resource = (IFile) input;
				final RepositoryMapping map = RepositoryMapping
						.getMapping(resource);
				final String gitPath = map.getRepoRelativePath(resource);

				final ITypedElement base = CompareUtils
						.getFileRevisionTypedElement(gitPath, commit1, map
								.getRepository());
				final ITypedElement next = CompareUtils
						.getFileRevisionTypedElement(gitPath, commit2, map
								.getRepository());
				if (base instanceof EmptyTypedElement
						&& next instanceof EmptyTypedElement) {
					// if the file is in neither commit1 nor commit2, show an
					// error
					RevCommit[] ids = new RevCommit[] { commit1, commit2 };
					StringBuilder commitList = new StringBuilder();
					for (RevCommit commit : ids) {
						commitList.append("\n"); //$NON-NLS-1$
						commitList.append(commit.getShortMessage());
						commitList.append(' ');
						commitList.append('[');
						commitList.append(commit.name());
						commitList.append(']');
					}
					String message = NLS.bind(
							UIText.GitHistoryPage_notContainedInCommits,
							gitPath, commitList.toString());
					IStatus errorStatus = new Status(IStatus.ERROR, Activator
							.getPluginId(), message);
					EgitUiEditorUtils
							.openErrorEditor(
									getPart(event).getSite().getPage(),
									errorStatus,
									UIText.CompareVersionsHandler_ErrorOpeningCompareEditorTitle,
									null);
					return null;
				}
				CompareEditorInput in = new GitCompareFileRevisionEditorInput(
						base, next, null);
				openInCompare(event, in);
			}
		}
		return null;
	}

	@Override
	public boolean isEnabled() {
		GitHistoryPage page = getPage();
		if (page == null)
			return false;
		if (!(page.getInput() instanceof IFile))
			return false;
		IStructuredSelection sel = getSelection(page);
		Object[] selected = sel.toArray();
		return selected.length == 2 && selected[0] instanceof RevCommit
				&& selected[1] instanceof RevCommit;
	}
}
