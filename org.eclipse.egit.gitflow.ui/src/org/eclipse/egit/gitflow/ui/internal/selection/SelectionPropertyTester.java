/*******************************************************************************
 * Copyright (C) 2015, Max Hohenegger <eclipse@hohenegger.eu>
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.egit.gitflow.ui.internal.selection;

import static org.eclipse.egit.gitflow.ui.Activator.error;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.egit.gitflow.Activator;
import org.eclipse.egit.gitflow.GitFlowRepository;
import org.eclipse.egit.ui.internal.selection.SelectionUtils;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jgit.lib.Repository;

/**
 * Testing Git Flow states.
 */
public class SelectionPropertyTester extends PropertyTester {
	private static final String IS_MASTER = "isMaster"; //$NON-NLS-1$

	private static final String IS_DEVELOP = "isDevelop"; //$NON-NLS-1$

	private static final String IS_HOTFIX = "isHotfix"; //$NON-NLS-1$

	private static final String IS_RELEASE = "isRelease"; //$NON-NLS-1$

	private static final String IS_INITIALIZED = "isInitialized"; //$NON-NLS-1$

	private static final String IS_FEATURE = "isFeature"; //$NON-NLS-1$

	private static final String HAS_DEFAULT_REMOTE = "hasDefaultRemote"; //$NON-NLS-1$

	@Override
	public boolean test(Object receiver, String property, Object[] args,
			Object expectedValue) {
		if (receiver == null) {
			return false;
		}
		Repository repository = getRepository((Collection<?>) receiver);
		if (repository == null || repository.isBare()) {
			return false;
		}
		GitFlowRepository gitFlowRepository = new GitFlowRepository(repository);
		try {
			if (IS_INITIALIZED.equals(property)) {
				return gitFlowRepository.getConfig().isInitialized();
			} else if (IS_FEATURE.equals(property)) {
				return gitFlowRepository.isFeature();
			} else if (IS_RELEASE.equals(property)) {
				return gitFlowRepository.isRelease();
			} else if (IS_HOTFIX.equals(property)) {
				return gitFlowRepository.isHotfix();
			} else if (IS_DEVELOP.equals(property)) {
				return gitFlowRepository.isDevelop();
			} else if (IS_MASTER.equals(property)) {
				return gitFlowRepository.isMaster();
			} else if (HAS_DEFAULT_REMOTE.equals(property)) {
				return gitFlowRepository.getConfig().hasDefaultRemote();
			}
		} catch (IOException e) {
			Activator.getDefault().getLog().log(error(e.getMessage(), e));
		}
		return false;
	}

	private Repository getRepository(Collection<?> collection) {
		if (collection.isEmpty()) {
			return null;
		}
		IStructuredSelection selection = null;
		Object first = collection.iterator().next();
		if (collection.size() == 1 && first instanceof ITextSelection) {
			selection = SelectionUtils
					.getStructuredSelection((ITextSelection) first);
		} else {
			selection = new StructuredSelection(new ArrayList<>(collection));
		}
		return SelectionUtils.getRepository(selection);

	}
}
